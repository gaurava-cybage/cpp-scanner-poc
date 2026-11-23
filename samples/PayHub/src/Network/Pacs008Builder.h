#ifndef PAYHUB_NETWORK_PACS008BUILDER_H_
#define PAYHUB_NETWORK_PACS008BUILDER_H_

#include <string>

#include "Payment.h"

namespace payhub::network {

// Builds a simplified ISO 20022 pacs.008 (FI-to-FI Customer Credit Transfer) XML message.
class Pacs008Builder {
public:
    explicit Pacs008Builder(std::string instructingAgentBic);

    std::string Build(const core::Payment& payment) const;

private:
    static std::string FormatAmount(std::int64_t amountMinor);
    static std::string Escape(const std::string& value);

    std::string instructingAgentBic_;
};

} // namespace payhub::network

#endif
