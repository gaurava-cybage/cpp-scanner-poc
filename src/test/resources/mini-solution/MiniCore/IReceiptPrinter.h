#pragma once

namespace tinypay {

class IReceiptPrinter {
public:
    virtual ~IReceiptPrinter() = default;
    virtual void print(int paymentId) = 0;
};

}
