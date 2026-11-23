#ifndef PAYHUB_NETWORK_WIREPROTOCOL_H_
#define PAYHUB_NETWORK_WIREPROTOCOL_H_

#include <cstdint>

namespace payhub::network {

#pragma pack(push, 1)

struct WireMessageHeader {
    std::uint8_t version;
    std::uint16_t messageType;
    std::uint32_t length;
    std::uint64_t sequenceNumber;
    std::uint32_t checksum;
};

struct PaymentSubmitMessage {
    WireMessageHeader header;
    std::uint8_t currencyCode[3];
    std::uint64_t amountInPaisa;
    std::uint8_t debtorBic[11];
    std::uint8_t creditorBic[11];
    std::uint8_t urgencyFlag;
};

#pragma pack(pop)

static_assert(sizeof(WireMessageHeader) == 15, "WireMessageHeader must be exactly 15 bytes");
static_assert(sizeof(PaymentSubmitMessage) == 41, "PaymentSubmitMessage must be exactly 41 bytes");

}

#endif
