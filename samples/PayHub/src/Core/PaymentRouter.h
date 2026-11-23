#pragma once
#include "IRailConfigRepository.h"
#include "Payment.h"

namespace payhub::core {

// Rail selection rules. Limits and bank participation come from reference data
// (IRailConfigRepository), not from hard-coded constants:
//   non-INR or non-IFSC agent                        -> SWIFT
//   INR within RTGS limits and bank on RTGS          -> RTGS
//   urgent, within IMPS limits and bank on IMPS      -> IMPS
//   bank on NEFT                                     -> NEFT
//   otherwise                                        -> Unassigned (payment is rejected)
class PaymentRouter {
public:
    explicit PaymentRouter(const IRailConfigRepository& railConfig);

    Rail SelectRail(const Payment& payment) const;

private:
    bool IsDomestic(const Payment& payment) const;
    bool IsEligible(const Payment& payment, Rail rail) const;

    const IRailConfigRepository& railConfig_;
};

} // namespace payhub::core
