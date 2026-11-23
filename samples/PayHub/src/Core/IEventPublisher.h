#pragma once
#include <string>

namespace payhub::core {

inline constexpr const char* kTopicPaymentSubmitted = "payments.submitted";
inline constexpr const char* kTopicPaymentSettled   = "payments.settled";
inline constexpr const char* kTopicPaymentRejected  = "payments.rejected";

// Payment event publishing. Implemented by messaging::KafkaEventPublisher.
class IEventPublisher {
public:
    virtual ~IEventPublisher() = default;
    virtual bool Publish(const std::string& topic, const std::string& key, const std::string& payload) = 0;
};

} // namespace payhub::core
