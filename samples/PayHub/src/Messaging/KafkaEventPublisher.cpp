#include "KafkaEventPublisher.h"

#include <stdexcept>

#include <librdkafka/rdkafka.h>

#include "Logger.h"

namespace payhub::messaging {

KafkaEventPublisher::KafkaEventPublisher(const std::string& bootstrapServers) {
    char error[512];
    rd_kafka_conf_t* conf = rd_kafka_conf_new();
    if (rd_kafka_conf_set(conf, "bootstrap.servers", bootstrapServers.c_str(), error, sizeof(error)) != RD_KAFKA_CONF_OK ||
        rd_kafka_conf_set(conf, "enable.idempotence", "true", error, sizeof(error)) != RD_KAFKA_CONF_OK) {
        rd_kafka_conf_destroy(conf);
        throw std::runtime_error(error);
    }

    producer_ = rd_kafka_new(RD_KAFKA_PRODUCER, conf, error, sizeof(error));
    if (producer_ == nullptr) {
        throw std::runtime_error(error);
    }
    PHUB_LOG_INFO("Kafka producer connected to " + bootstrapServers);
}

KafkaEventPublisher::~KafkaEventPublisher() {
    if (producer_ != nullptr) {
        rd_kafka_flush(producer_, 5000);
        rd_kafka_destroy(producer_);
    }
}

bool KafkaEventPublisher::Publish(const std::string& topic, const std::string& key, const std::string& payload) {
    rd_kafka_resp_err_t err = rd_kafka_producev(
        producer_,
        RD_KAFKA_V_TOPIC(topic.c_str()),
        RD_KAFKA_V_KEY(const_cast<char*>(key.data()), key.size()),
        RD_KAFKA_V_VALUE(const_cast<char*>(payload.data()), payload.size()),
        RD_KAFKA_V_MSGFLAGS(RD_KAFKA_MSG_F_COPY),
        RD_KAFKA_V_END);

    if (err != RD_KAFKA_RESP_ERR_NO_ERROR) {
        PHUB_LOG_ERROR(std::string("Kafka publish to ") + topic + " failed: " + rd_kafka_err2str(err));
        return false;
    }
    rd_kafka_poll(producer_, 0);
    return true;
}

} // namespace payhub::messaging
