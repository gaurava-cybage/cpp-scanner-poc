#include "PaymentHubService.h"

#include <set>

#include "Logger.h"

namespace payhub::core {

namespace {
const std::set<std::string> kSupportedCurrencies = {"INR", "USD", "EUR", "GBP"};
}

PaymentHubService::PaymentHubService(IPaymentRepository& repository,
                                     ICache<std::string, Payment>& cache,
                                     IEventPublisher& publisher,
                                     IRailConnector& railConnector,
                                     ISanctionsScreener& screener,
                                     const PaymentRouter& router)
    : repository_(repository),
      cache_(cache),
      publisher_(publisher),
      railConnector_(railConnector),
      screener_(screener),
      router_(router) {}

// Processing flow:
//   duplicate check (cache) -> Validate -> Screen (Python sanctions screening)
//   -> SelectRail (PaymentRouter, rail_config/participant_banks) -> Submit (Java rail-connector-service, pacs.008)
//   -> Save (SQLite) -> Put (cache) -> Publish (Kafka "payments.submitted")
Payment PaymentHubService::Process(Payment payment) {
    if (auto existing = cache_.Get(payment.EndToEndId())) {
        PHUB_LOG_WARN("Duplicate instruction " + payment.EndToEndId() + " - returning original result");
        return *existing;
    }

    if (!Validate(payment)) {
        return Reject(std::move(payment), "validation failed");
    }
    payment.SetStatus(PaymentStatus::Validated);

    payment.SetScreeningScore(screener_.Screen(payment));
    if (payment.ScreeningScore() >= kSanctionsHitThreshold) {
        return Reject(std::move(payment), "sanctions hit, score " + std::to_string(payment.ScreeningScore()));
    }
    payment.SetStatus(PaymentStatus::Screened);

    payment.SetRail(router_.SelectRail(payment));
    if (payment.GetRail() == Rail::Unassigned) {
        return Reject(std::move(payment), "no eligible rail");
    }
    payment.SetStatus(PaymentStatus::Routed);

    RailSubmission submission = railConnector_.Submit(payment);
    if (!submission.accepted) {
        return Reject(std::move(payment), std::string("rail ") + ToString(payment.GetRail()) +
                                              " rejected, reason " + submission.reasonCode);
    }
    payment.SetRailReference(submission.railReference);
    payment.SetStatus(PaymentStatus::Submitted);

    if (!repository_.Save(payment)) {
        payment.SetStatus(PaymentStatus::Failed);
        PHUB_LOG_ERROR("Could not persist payment " + payment.EndToEndId());
        return payment;
    }
    cache_.Put(payment.EndToEndId(), payment);
    publisher_.Publish(kTopicPaymentSubmitted, payment.EndToEndId(), payment.ToJson());

    PHUB_LOG_INFO("Payment " + payment.EndToEndId() + " submitted on " + ToString(payment.GetRail()) +
                  ", ref " + payment.RailReference());
    return payment;
}

// Settlement confirmation (callback from the rail connector):
//   Load (cache -> SQLite) -> UpdateStatus (SQLite) -> Put (cache)
//   -> Publish (Kafka "payments.settled")
std::optional<Payment> PaymentHubService::ConfirmSettlement(const std::string& endToEndId,
                                                            const std::string& railReference) {
    auto payment = Load(endToEndId);
    if (!payment || payment->Status() != PaymentStatus::Submitted) {
        PHUB_LOG_WARN("Settlement ignored for " + endToEndId + ": not in SUBMITTED state");
        return std::nullopt;
    }

    payment->SetStatus(PaymentStatus::Settled);
    payment->SetRailReference(railReference);
    repository_.UpdateStatus(endToEndId, PaymentStatus::Settled, railReference);
    cache_.Put(endToEndId, *payment);
    publisher_.Publish(kTopicPaymentSettled, endToEndId, payment->ToJson());
    return payment;
}

std::optional<Payment> PaymentHubService::GetStatus(const std::string& endToEndId) {
    return Load(endToEndId);
}

// Read-through cache: cache -> repository -> cache.
std::optional<Payment> PaymentHubService::Load(const std::string& endToEndId) {
    if (auto cached = cache_.Get(endToEndId)) {
        return cached;
    }
    auto stored = repository_.FindById(endToEndId);
    if (stored) {
        cache_.Put(endToEndId, *stored);
    }
    return stored;
}

bool PaymentHubService::Validate(const Payment& payment) const {
    return !payment.EndToEndId().empty()
        && payment.EndToEndId().size() <= 35            // ISO 20022 Max35Text
        && !payment.DebtorAccount().empty()
        && !payment.CreditorAccount().empty()
        && payment.DebtorAccount() != payment.CreditorAccount()
        && (payment.CreditorAgent().size() == 11 || payment.CreditorAgent().size() == 8)   // IFSC or BIC8/11
        && payment.AmountMinor() > 0
        && kSupportedCurrencies.count(payment.Currency()) == 1;
}

Payment PaymentHubService::Reject(Payment payment, const std::string& reason) {
    payment.SetStatus(PaymentStatus::Rejected);
    repository_.Save(payment);
    cache_.Put(payment.EndToEndId(), payment);
    publisher_.Publish(kTopicPaymentRejected, payment.EndToEndId(), payment.ToJson());
    PHUB_LOG_WARN("Payment " + payment.EndToEndId() + " rejected: " + reason);
    return payment;
}

} // namespace payhub::core
