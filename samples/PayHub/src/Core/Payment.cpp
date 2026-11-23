#include "Payment.h"

#include <sstream>
#include <utility>

namespace payhub::core {

const char* ToString(Rail rail) {
    switch (rail) {
    case Rail::Unassigned: return "UNASSIGNED";
    case Rail::IMPS:       return "IMPS";
    case Rail::NEFT:       return "NEFT";
    case Rail::RTGS:       return "RTGS";
    case Rail::SWIFT:      return "SWIFT";
    }
    return "UNKNOWN";
}

const char* ToString(PaymentStatus status) {
    switch (status) {
    case PaymentStatus::Received:  return "RECEIVED";
    case PaymentStatus::Validated: return "VALIDATED";
    case PaymentStatus::Screened:  return "SCREENED";
    case PaymentStatus::Rejected:  return "REJECTED";
    case PaymentStatus::Routed:    return "ROUTED";
    case PaymentStatus::Submitted: return "SUBMITTED";
    case PaymentStatus::Settled:   return "SETTLED";
    case PaymentStatus::Failed:    return "FAILED";
    }
    return "UNKNOWN";
}

const char* ToString(Priority priority) {
    return priority == Priority::Urgent ? "URGENT" : "NORMAL";
}

Payment::Payment(std::string endToEndId, std::string debtorAccount, std::string creditorAccount,
                 std::string creditorAgent, std::int64_t amountMinor, std::string currency, Priority priority)
    : endToEndId_(std::move(endToEndId)),
      debtorAccount_(std::move(debtorAccount)),
      creditorAccount_(std::move(creditorAccount)),
      creditorAgent_(std::move(creditorAgent)),
      amountMinor_(amountMinor),
      currency_(std::move(currency)),
      priority_(priority) {}

std::string Payment::ToJson() const {
    std::ostringstream out;
    out << "{\"endToEndId\":\"" << endToEndId_ << "\""
        << ",\"debtorAccount\":\"" << debtorAccount_ << "\""
        << ",\"creditorAccount\":\"" << creditorAccount_ << "\""
        << ",\"creditorAgent\":\"" << creditorAgent_ << "\""
        << ",\"amountMinor\":" << amountMinor_
        << ",\"currency\":\"" << currency_ << "\""
        << ",\"priority\":\"" << ToString(priority_) << "\""
        << ",\"rail\":\"" << ToString(rail_) << "\""
        << ",\"status\":\"" << ToString(status_) << "\""
        << ",\"railReference\":\"" << railReference_ << "\""
        << ",\"screeningScore\":" << screeningScore_ << "}";
    return out.str();
}

} // namespace payhub::core
