package com.yanban.sandboxbroker;
import java.util.concurrent.ConcurrentHashMap; import org.springframework.stereotype.Component;
@Component class SandboxProcessRegistry {
 private final ConcurrentHashMap<String,ActiveProcess> active=new ConcurrentHashMap<>();
 void register(String id,Process process,boolean cancellable){active.put(id,new ActiveProcess(process,cancellable));}
 void clear(String id,Process process){active.computeIfPresent(id,(key,current)->current.process()==process?null:current);}
 void cancel(String id){ActiveProcess current=active.get(id);if(current!=null&&current.cancellable())terminate(current.process());}
 void terminate(String id){ActiveProcess current=active.get(id);if(current!=null)terminate(current.process());}
 private void terminate(Process process){process.destroy();try{if(!process.waitFor(2,java.util.concurrent.TimeUnit.SECONDS))process.destroyForcibly();}catch(InterruptedException e){Thread.currentThread().interrupt();process.destroyForcibly();}}
 private record ActiveProcess(Process process,boolean cancellable){}
}
