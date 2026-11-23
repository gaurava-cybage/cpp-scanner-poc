#pragma once
#include <optional>
#include <string>

#include "Payment.h"

namespace payhub::core {

// Payment store with audit trail. Implemented by storage::SqlitePaymentRepository.
class IPaymentRepository {
public:
    virtual ~IPaymentRepository() = default;
    virtual bool Save(const Payment& payment) = 0;
    virtual bool UpdateStatus(const std::string& endToEndId, PaymentStatus status,
                              const std::string& railReference) = 0;
    virtual std::optional<Payment> FindById(const std::string& endToEndId) = 0;
};

} // namespace payhub::core
