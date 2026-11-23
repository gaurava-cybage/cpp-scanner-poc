#pragma once

#ifdef _WIN32
    #define PAYHUB_EXPORT __declspec(dllexport)
    #define PAYHUB_IMPORT __declspec(dllimport)
#else
    #define PAYHUB_EXPORT
    #define PAYHUB_IMPORT
#endif

#define DECLARE_INTERFACE_METHOD(RetType, MethodName, Params) \
    virtual RetType MethodName Params = 0

#define IMPLEMENT_GETTER(Type, Name, Member) \
    Type Get##Name() const { return Member; }

#define IMPLEMENT_SETTER(Type, Name, Member) \
    void Set##Name(Type value) { Member = value; }

#define IMPLEMENT_PROPERTY(Type, Name, Member) \
    IMPLEMENT_GETTER(Type, Name, Member) \
    IMPLEMENT_SETTER(Type, Name, Member)

#define BEGIN_INTERFACE(InterfaceName) \
    class PAYHUB_EXPORT InterfaceName { \
    public:

#define END_INTERFACE() \
        virtual ~InterfaceName() = default; \
        InterfaceName(const InterfaceName&) = delete; \
        InterfaceName& operator=(const InterfaceName&) = delete; \
    };

#define STATIC_INTERFACE_ID(ClassName) \
    static constexpr const char* InterfaceId = #ClassName; \
    virtual const char* GetInterfaceId() const { return InterfaceId; }
