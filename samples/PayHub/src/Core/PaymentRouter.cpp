#include "PaymentRouter.h"

namespace payhub::core {

PaymentRouter::PaymentRouter(const IRailConfigRepository& railConfig)
    : railConfig_(railConfig) {}

Rail PaymentRouter::SelectRail(const Payment& payment) const {
    if (!IsDomestic(payment)) {
        return IsEligible(payment, Rail::SWIFT) ? Rail::SWIFT : Rail::Unassigned;
    }
    if (IsEligible(payment, Rail::RTGS)) {
        return Rail::RTGS;
    }
    if (payment.GetPriority() == Priority::Urgent && IsEligible(payment, Rail::IMPS)) {
        return Rail::IMPS;
    }
    if (IsEligible(payment, Rail::NEFT)) {
        return Rail::NEFT;
    }
    return Rail::Unassigned;
}

bool PaymentRouter::IsDomestic(const Payment& payment) const {
    // Domestic = INR and an 11-character IFSC creditor agent.
    return payment.Currency() == "INR" && payment.CreditorAgent().size() == 11;
}

bool PaymentRouter::IsEligible(const Payment& payment, Rail rail) const {
    auto limits = railConfig_.GetLimits(rail);
    if (!limits || !limits->active) {
        return false;
    }
    if (payment.AmountMinor() < limits->minAmountMinor) {
        return false;
    }
    if (limits->maxAmountMinor && payment.AmountMinor() > *limits->maxAmountMinor) {
        return false;
    }
    if (rail == Rail::SWIFT) {
        return true;
    }
    return railConfig_.BankSupportsRail(payment.CreditorAgent().substr(0, 4), rail);
}

} // namespace payhub::core
