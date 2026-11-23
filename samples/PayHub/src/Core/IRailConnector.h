#pragma once
#include <string>

#include "Payment.h"

namespace payhub::core {

struct RailSubmission {
    bool accepted = false;
    std::string railReference;   // UTR for domestic rails, UETR for SWIFT
    std::string reasonCode;      // ISO 20022 reason code when rejected, e.g. "AC01"
};

// Clearing-rail connectivity. Implemented by network::HttpRailConnector,
// which calls the external Java "rail-connector-service".
class IRailConnector {
public:
    virtual ~IRailConnector() = default;
    virtual RailSubmission Submit(const Payment& payment) = 0;
};

} // namespace payhub::core
