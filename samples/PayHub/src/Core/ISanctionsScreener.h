#pragma once
#include "Payment.h"

namespace payhub::core {

// Sanctions / AML screening, 0 (clear) to 100 (confirmed hit).
// Implemented by integration::PythonSanctionsScreener, which runs the
// external Python script scripts/sanctions_screen.py.
class ISanctionsScreener {
public:
    virtual ~ISanctionsScreener() = default;
    virtual int Screen(const Payment& payment) = 0;
};

} // namespace payhub::core
