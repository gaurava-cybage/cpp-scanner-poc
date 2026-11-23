#pragma once
#include <cstdint>
#include <string>

namespace payhub::core {

enum class Priority { Normal, Urgent };

// Clearing rails the hub can route to.
enum class Rail { Unassigned, IMPS, NEFT, RTGS, SWIFT };

enum class PaymentStatus { Received, Validated, Screened, Rejected, Routed, Submitted, Settled, Failed };

const char* ToString(Rail rail);
const char* ToString(PaymentStatus status);
const char* ToString(Priority priority);

// A credit transfer instruction (modelled on ISO 20022 pain.001 / pacs.008).
// Amounts are in minor units (paise / cents).
class Payment {
public:
    Payment() = default;
    Payment(std::string endToEndId, std::string debtorAccount, std::string creditorAccount,
            std::string creditorAgent, std::int64_t amountMinor, std::string currency, Priority priority);

    std::string ToJson() const;

    const std::string& EndToEndId() const { return endToEndId_; }
    const std::string& DebtorAccount() const { return debtorAccount_; }
    const std::string& CreditorAccount() const { return creditorAccount_; }
    const std::string& CreditorAgent() const { return creditorAgent_; }   // IFSC or BIC
    std::int64_t AmountMinor() const { return amountMinor_; }
    const std::string& Currency() const { return currency_; }
    Priority GetPriority() const { return priority_; }

    PaymentStatus Status() const { return status_; }
    void SetStatus(PaymentStatus status) { status_ = status; }

    Rail GetRail() const { return rail_; }
    void SetRail(Rail rail) { rail_ = rail; }

    const std::string& RailReference() const { return railReference_; }   // UTR / UETR
    void SetRailReference(std::string reference) { railReference_ = std::move(reference); }

    int ScreeningScore() const { return screeningScore_; }
    void SetScreeningScore(int score) { screeningScore_ = score; }

private:
    std::string endToEndId_;
    std::string debtorAccount_;
    std::string creditorAccount_;
    std::string creditorAgent_;
    std::int64_t amountMinor_ = 0;
    std::string currency_;
    Priority priority_ = Priority::Normal;
    PaymentStatus status_ = PaymentStatus::Received;
    Rail rail_ = Rail::Unassigned;
    std::string railReference_;
    int screeningScore_ = 0;
};

} // namespace payhub::core
