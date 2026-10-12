package p;
import com.alipay.sofa.runtime.api.annotation.SofaService;
import com.alipay.sofa.runtime.api.annotation.SofaReference;
interface Api { void run(); }
@SofaService class Local implements Api { public void run() {} }
@SofaService(uniqueId="other") class Other implements Api { public void run() {} }
class Remote implements Api { public void run() {} }
class Consumer {
    @SofaReference Api local;
    @SofaReference(uniqueId="other") Api other;
    @SofaReference(uniqueId="absent") Api absent;
}
class Listener implements com.alipay.common.event.UniformEventMessageListener {
    public void onUniformEvent(com.alipay.common.event.UniformEvent event,
                               com.alipay.common.event.UniformEventContext context) {}
    public void onUniformEvent(String unrelated) {}
}
