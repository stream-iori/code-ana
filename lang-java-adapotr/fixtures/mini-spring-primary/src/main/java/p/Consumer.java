package p;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.stereotype.Component;

interface Api {}
interface FactoryApi {}
interface FakeApi {}
interface XmlApi {}
@interface Primary {}

@Component("ordinary") class Ordinary implements Api {}
@Component("preferred") @org.springframework.context.annotation.Primary
class Preferred implements Api {}

@Component class Factories {
    @Bean FactoryApi plainFactory() { return null; }
    @Bean @org.springframework.context.annotation.Primary
    FactoryApi primaryFactory() { return null; }
}

@Component @Primary class FakePrimary implements FakeApi {}
@Component class OtherFake implements FakeApi {}
@org.springframework.context.annotation.Primary class XmlAnnotated implements XmlApi {}
class XmlOther implements XmlApi {}

public class Consumer {
    @Autowired Api api;
    @Autowired @Qualifier("ordinary") Api qualified;
    @Autowired @Qualifier("absent") Api missing;
    @Autowired @Qualifier("primaryFactory") Api incompatible;
    @Autowired FactoryApi factory;
    @Autowired FakeApi fake;
    @Autowired XmlApi xml;
    @Autowired java.util.List<Api> all;
    @Autowired public Consumer(Api constructorApi) {}
    public void setApi(Api api) {}
}
