#pragma once
#include <optional>
#include <string>

#include "ICache.h"
#include "IEventPublisher.h"
#include "IPaymentRepository.h"
#include "IRailConnector.h"
#include "ISanctionsScreener.h"
#include "Payment.h"
#include "PaymentRouter.h"

namespace payhub::core {

inline constexpr int kSanctionsHitThreshold = 80;

// Central payment orchestration. Depends only on interfaces (plus PaymentRouter,
// which reads rail reference data through IRailConfigRepository); concrete implementations are wired together in App/main.cpp.
class PaymentHubService {
public:
    PaymentHubService(IPaymentRepository& repository,
                      ICache<std::string, Payment>& cache,
                      IEventPublisher& publisher,
                      IRailConnector& railConnector,
                      ISanctionsScreener& screener,
                      const PaymentRouter& router);

    Payment Process(Payment payment);
    std::optional<Payment> ConfirmSettlement(const std::string& endToEndId, const std::string& railReference);
    std::optional<Payment> GetStatus(const std::string& endToEndId);

private:
    bool Validate(const Payment& payment) const;
    Payment Reject(Payment payment, const std::string& reason);
    std::optional<Payment> Load(const std::string& endToEndId);

    IPaymentRepository& repository_;
    ICache<std::string, Payment>& cache_;
    IEventPublisher& publisher_;
    IRailConnector& railConnector_;
    ISanctionsScreener& screener_;
    const PaymentRouter& router_;
};

} // namespace payhub::core
