#pragma once
#include <string>

#include "IEventPublisher.h"

struct rd_kafka_s;

namespace payhub::messaging {

// Publishes payment events to Apache Kafka via librdkafka (C API).
// Downstream consumers: Java "accounting-service" (payments.settled),
// Java "customer-notification-service" (all payment topics).
class KafkaEventPublisher : public core::IEventPublisher {
public:
    explicit KafkaEventPublisher(const std::string& bootstrapServers);
    ~KafkaEventPublisher() override;

    KafkaEventPublisher(const KafkaEventPublisher&) = delete;
    KafkaEventPublisher& operator=(const KafkaEventPublisher&) = delete;

    bool Publish(const std::string& topic, const std::string& key, const std::string& payload) override;

private:
    rd_kafka_s* producer_ = nullptr;
};

} // namespace payhub::messaging
