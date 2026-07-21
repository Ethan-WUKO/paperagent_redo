package com.yanban.sandboxbroker;

import com.fasterxml.jackson.databind.*;
import com.yanban.sandbox.contract.*;
import java.io.*;
import java.nio.charset.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix="yanban.broker",name="enabled",havingValue="true")
class SandboxWorker {
    private static final Duration LEASE=Duration.ofSeconds(30);
    private static final long CREATE_TIMEOUT_MILLIS=60_000L;
    private final SandboxLeaseService leases; private final BrokerProperties properties; private final ObjectMapper json;
    private final SandboxProcessRegistry processes; private final ProviderEnvironment providerEnvironment; private final String owner=UUID.randomUUID().toString();
    SandboxWorker(SandboxLeaseService leases,BrokerProperties properties,ObjectMapper json,SandboxProcessRegistry processes,ProviderEnvironment providerEnvironment){this.leases=leases;this.properties=properties;this.json=json;this.processes=processes;this.providerEnvironment=providerEnvironment;}

    @Scheduled(fixedDelayString="${yanban.broker.poll-delay-ms:1000}")
    void poll(){leases.claim(owner,LEASE).ifPresent(lease -> {
        try { runClaim(lease); }
        catch (RuntimeException lost) { recoverUnexpected(lease); }
    });}

    private void runClaim(SandboxLeaseService.Lease lease){
        SandboxExecutionEntity entity=leases.owned(lease);
        SandboxDispatch request=read(entity.requestJson());
        Path root=workspace(lease.executionId(),lease.recovery());
        SbxCommandFactory commands=new SbxCommandFactory(properties.getSbxExecutable());
        if(lease.recovery()){
            boolean clean=cleanup(lease,commands,entity.sandboxName(),root);
            finishRecovery(lease,entity,request,clean);return;
        }
        Instant startedAt=leases.now(lease); ExecResult result=null; SandboxExecutionStatus desired=SandboxExecutionStatus.FAILED; SandboxErrorCode error=null;
        try{
            throwIfCancelled(lease);
            materialize(root,request.files()); leases.transition(lease,"MATERIALIZING",checkpoint("MATERIALIZED",entity.sandboxName()));
            throwIfCancelled(lease);
            // Interrupting sbx create can leave the provider holding the workspace before the sandbox is listable.
            // Let the bounded create finish, then honor cancellation before policy or user code can run.
            requireOk(execute(lease,commands.create(entity.sandboxName(),root,request.cpus(),request.memoryBytes()),CREATE_TIMEOUT_MILLIS,65536,false),"create");
            throwIfCancelled(lease);
            leases.transition(lease,"CREATED",checkpoint("CREATED",entity.sandboxName()));
            requireOk(execute(lease,commands.denyAllNetwork(entity.sandboxName()),30000,65536),"network policy");
            ExecResult policy=execute(lease,commands.verifyNetworkPolicy(entity.sandboxName()),30000,262144);
            requireOk(policy,"policy verification"); requireDenyAll(policy.stdout());
            leases.transition(lease,"POLICY_APPLIED",checkpoint("POLICY_APPLIED",entity.sandboxName()));
            leases.transition(lease,"RUNNING",checkpoint("RUNNING",entity.sandboxName()));
            result=execute(lease,commands.exec(entity.sandboxName(),request.argv()),request.timeoutMillis(),request.maxOutputBytes());
            desired=result.exitCode()==0?SandboxExecutionStatus.SUCCEEDED:SandboxExecutionStatus.FAILED;
        }catch(OutputLimitException ex){desired=SandboxExecutionStatus.FAILED;error=SandboxErrorCode.PROVIDER_REJECTED;
        }catch(TimeoutException ex){desired=SandboxExecutionStatus.TIMED_OUT;error=SandboxErrorCode.TIMED_OUT;
        }catch(CancelledException ex){desired=SandboxExecutionStatus.CANCELLED;error=SandboxErrorCode.CANCELLED;
        }catch(Exception ex){desired=SandboxExecutionStatus.FAILED;error=SandboxErrorCode.PROVIDER_REJECTED;}
        if(desired==SandboxExecutionStatus.SUCCEEDED&&leases.cancellationRequested(lease)){desired=SandboxExecutionStatus.CANCELLED;error=SandboxErrorCode.CANCELLED;}
        SandboxReceipt receipt=receipt(lease.executionId(),request,desired,result,error,startedAt,leases.now(lease));
        String receiptJson=write(receipt); String digest=sha256(receiptJson);
        leases.transition(lease,pendingCleanupStatus(desired),checkpoint("CLEANUP_REQUIRED",entity.sandboxName()));
        if(!cleanup(lease,commands,entity.sandboxName(),root)){leases.terminal(lease,"CLEANUP_FAILED",null,null,"CLEANUP_FAILED");return;}
        if(desired==SandboxExecutionStatus.SUCCEEDED&&!leases.terminalSuccessIfNotCancelled(lease,digest,receiptJson)){
            Instant now=leases.now(lease);SandboxReceipt cancelled=receipt(lease.executionId(),request,SandboxExecutionStatus.CANCELLED,result,SandboxErrorCode.CANCELLED,startedAt,now);String encoded=write(cancelled);
            leases.terminal(lease,"CANCELLED",sha256(encoded),encoded,"CANCELLED");
        }else if(desired!=SandboxExecutionStatus.SUCCEEDED)leases.terminal(lease,desired.name(),digest,receiptJson,error==null?null:error.name());
    }

    static long createTimeoutMillis(){return CREATE_TIMEOUT_MILLIS;}

    private void recoverUnexpected(SandboxLeaseService.Lease lease) {
        try {
            SandboxExecutionEntity entity = leases.owned(lease);
            Path root = workspace(lease.executionId(), true);
            SbxCommandFactory commands = new SbxCommandFactory(properties.getSbxExecutable());
            leases.transition(lease, "FAILED_PENDING_CLEANUP", checkpoint("CLEANUP_REQUIRED", entity.sandboxName()));
            if (cleanup(lease, commands, entity.sandboxName(), root))
                leases.terminal(lease, "FAILED", null, null, "PROVIDER_REJECTED");
            else leases.terminal(lease, "CLEANUP_FAILED", null, null, "CLEANUP_FAILED");
        } catch (RuntimeException ignored) {
            // Durable non-terminal state remains claimable after its database-time lease expires.
        }
    }

    private void finishRecovery(SandboxLeaseService.Lease lease,SandboxExecutionEntity entity,SandboxDispatch request,boolean clean){
        if(!clean){leases.terminal(lease,"CLEANUP_FAILED",null,null,"CLEANUP_FAILED");return;}
        if(entity.receiptJson()!=null){SandboxReceipt receipt=readReceipt(entity.receiptJson());leases.terminal(lease,receipt.status().name(),entity.receiptDigest(),entity.receiptJson(),receipt.errorCode()==null?null:receipt.errorCode().name());return;}
        SandboxExecutionStatus status=entity.cancelRequested()?SandboxExecutionStatus.CANCELLED:SandboxExecutionStatus.FAILED;
        SandboxErrorCode error=entity.cancelRequested()?SandboxErrorCode.CANCELLED:SandboxErrorCode.PROVIDER_REJECTED;
        Instant now=leases.now(lease);SandboxReceipt receipt=receipt(lease.executionId(),request,status,null,error,now,now);String encoded=write(receipt);
        leases.terminal(lease,status.name(),sha256(encoded),encoded,error.name());
    }
    private String pendingCleanupStatus(SandboxExecutionStatus desired){return switch(desired){
        case SUCCEEDED -> "SUCCEEDED_PENDING_CLEANUP"; case TIMED_OUT -> "TIMED_OUT_PENDING_CLEANUP";
        case CANCELLED -> "CANCEL_REQUESTED"; default -> "FAILED_PENDING_CLEANUP";};}
    private boolean cleanup(SandboxLeaseService.Lease lease,SbxCommandFactory commands,String name,Path root){
        try{leases.transition(lease,"CLEANING",checkpoint("CLEANING",name));processes.terminate(lease.executionId());}
        catch(Exception ex){return false;}
        for(int attempt=1;attempt<=3;attempt++){
            try{
                execute(lease,commands.stop(name),10000,65536,false);
                execute(lease,commands.remove(name),20000,65536,false);
                ExecResult list=execute(lease,commands.list(),10000,1048576,false);
                if(list.exitCode()==0&&!sandboxExists(list.stdout(),name)&&deleteWorkspace(root))return true;
            }catch(Exception ignored){ }
            if(attempt<3){
                try{Thread.sleep(250L*attempt);}catch(InterruptedException interrupted){Thread.currentThread().interrupt();return false;}
            }
        }
        return false;
    }
    private ExecResult execute(SandboxLeaseService.Lease lease,List<String> argv,long timeout,long limit)throws Exception{
        return execute(lease,argv,timeout,limit,true);
    }
    private ExecResult execute(SandboxLeaseService.Lease lease,List<String> argv,long timeout,long limit,boolean observeCancellation)throws Exception{
        ProcessBuilder builder=new ProcessBuilder(argv).directory(Path.of(properties.getWorkspaceRoot()).toFile());providerEnvironment.apply(builder);
        Process process=builder.start();processes.register(lease.executionId(),process,observeCancellation);AtomicLong budget=new AtomicLong(limit);AtomicReference<Throwable> failure=new AtomicReference<>();
        ByteArrayOutputStream stdout=new ByteArrayOutputStream(),stderr=new ByteArrayOutputStream();
        Thread out=reader(process.getInputStream(),stdout,budget,failure),err=reader(process.getErrorStream(),stderr,budget,failure);out.start();err.start();
        try{long deadline=System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(timeout);long heartbeat=System.nanoTime();
            while(process.isAlive()){
                if(failure.get()!=null){terminate(process);throw failureException(failure.get());}
                if(System.nanoTime()>=deadline){terminate(process);throw new TimeoutException();}
                if(System.nanoTime()-heartbeat>=TimeUnit.SECONDS.toNanos(10)){leases.heartbeat(lease,LEASE);heartbeat=System.nanoTime();}
                SandboxExecutionEntity state=leases.owned(lease);if(observeCancellation&&state.cancelRequested()){terminate(process);throw new CancelledException();}
                process.waitFor(100,TimeUnit.MILLISECONDS);
            }
            out.join(5000);err.join(5000);
            if(out.isAlive()||err.isAlive()||failure.get()!=null)throw new IOException("sandbox output reader failed");
            if(budget.get()<0){terminate(process);throw new OutputLimitException();}
            SandboxExecutionEntity state=leases.owned(lease);if(observeCancellation&&state.cancelRequested())throw new CancelledException();
            return new ExecResult(process.exitValue(),decode(stdout),decode(stderr));
        }finally{processes.clear(lease.executionId(),process);}
    }
    private Exception failureException(Throwable failure){return failure instanceof OutputLimitException limit?limit:new IOException("sandbox output reader failed",failure);}
    private void throwIfCancelled(SandboxLeaseService.Lease lease)throws CancelledException{if(leases.cancellationRequested(lease))throw new CancelledException();}
    private Thread reader(InputStream input,ByteArrayOutputStream output,AtomicLong budget,AtomicReference<Throwable> failure){return new Thread(()->{try(input){byte[] b=new byte[8192];int n;while((n=input.read(b))>=0){long left=budget.addAndGet(-n);if(left<0){failure.compareAndSet(null,new OutputLimitException());return;}output.write(b,0,n);}}catch(Throwable ex){failure.compareAndSet(null,ex);}},"sandbox-output-reader");}
    private String decode(ByteArrayOutputStream bytes)throws CharacterCodingException{return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).decode(java.nio.ByteBuffer.wrap(bytes.toByteArray())).toString();}
    private void requireDenyAll(String value)throws Exception{JsonNode root=json.readTree(value);if(!containsRule(root))throw new IllegalStateException("active scoped deny-all rule missing");}
    private boolean containsRule(JsonNode node){if(node==null)return false;if(node.isObject()){boolean legacy="local".equals(node.path("source").asText())&&"network".equals(node.path("type").asText())&&node.path("active").asBoolean(false)&&"**".equals(node.path("resource").asText());boolean current=("local".equals(node.path("origin").asText())||"scoped".equals(node.path("origin").asText()))&&"network".equals(node.path("resource_type").asText())&&"active".equals(node.path("status").asText())&&containsResource(node.path("resources"),"**");if("deny".equals(node.path("decision").asText())&&(legacy||current))return true;var fields=node.fields();while(fields.hasNext())if(containsRule(fields.next().getValue()))return true;}else if(node.isArray())for(JsonNode child:node)if(containsRule(child))return true;return false;}
    private boolean containsResource(JsonNode resources,String expected){if(!resources.isArray())return false;for(JsonNode resource:resources)if(resource.isTextual()&&expected.equals(resource.asText()))return true;return false;}
    private boolean sandboxExists(String value,String name)throws Exception{JsonNode root=json.readTree(value);return exactName(root,name);}
    private boolean exactName(JsonNode node,String name){if(node==null)return false;if(node.isObject()){if(name.equals(node.path("name").asText()))return true;var it=node.fields();while(it.hasNext())if(exactName(it.next().getValue(),name))return true;}else if(node.isArray())for(JsonNode child:node)if(exactName(child,name))return true;return false;}
    private void materialize(Path root,Map<String,String> files)throws Exception{Files.createDirectory(root);Path canonical=root.toRealPath(LinkOption.NOFOLLOW_LINKS);for(var file:files.entrySet()){Path relative=Path.of(file.getKey());if(relative.isAbsolute()||!relative.normalize().equals(relative)||java.util.stream.StreamSupport.stream(relative.spliterator(),false).anyMatch(part->"..".equals(part.toString())||".".equals(part.toString())))throw new IOException("unsafe path");Path parent=root;for(Path segment:relative.getParent()==null?List.<Path>of():relative.getParent()){parent=parent.resolve(segment.toString());try{Files.createDirectory(parent);}catch(FileAlreadyExistsException exists){if(!Files.isDirectory(parent,LinkOption.NOFOLLOW_LINKS))throw new IOException("workspace alias",exists);}if(Files.isSymbolicLink(parent)||!parent.toRealPath(LinkOption.NOFOLLOW_LINKS).startsWith(canonical))throw new IOException("workspace alias");}Path target=root.resolve(relative);Files.writeString(target,file.getValue(),StandardOpenOption.CREATE_NEW,LinkOption.NOFOLLOW_LINKS);}}
    private Path workspace(String id,boolean recovery){try{Path base=Path.of(properties.getWorkspaceRoot()).toRealPath(LinkOption.NOFOLLOW_LINKS);Path result=base.resolve(id).normalize();if(!result.getParent().equals(base)||(!recovery&&Files.exists(result,LinkOption.NOFOLLOW_LINKS)))throw new IllegalStateException("workspace collision");return result;}catch(IOException ex){throw new IllegalStateException("workspace unavailable",ex);}}
    private boolean deleteWorkspace(Path root)throws Exception{if(!Files.exists(root,LinkOption.NOFOLLOW_LINKS))return true;Path base=Path.of(properties.getWorkspaceRoot()).toRealPath(LinkOption.NOFOLLOW_LINKS);Path real=root.toRealPath(LinkOption.NOFOLLOW_LINKS);if(!real.getParent().equals(base)||Files.isSymbolicLink(real))return false;try(var walk=Files.walk(real)){for(Path path:walk.sorted(Comparator.reverseOrder()).toList()){if(Files.isSymbolicLink(path)||!path.normalize().startsWith(real))return false;Files.delete(path);}}return !Files.exists(real,LinkOption.NOFOLLOW_LINKS);}
    private SandboxDispatch read(String value){try{return json.readValue(value,SandboxDispatch.class);}catch(Exception ex){throw new IllegalStateException("stored sandbox request invalid",ex);}}
    private SandboxReceipt readReceipt(String value){try{return json.readValue(value,SandboxReceipt.class);}catch(Exception ex){throw new IllegalStateException("stored sandbox receipt invalid",ex);}}
    private String checkpoint(String phase,String name){return "{\"phase\":\""+phase+"\",\"sandboxName\":\""+name+"\"}";}
    private SandboxReceipt receipt(String id,SandboxDispatch r,SandboxExecutionStatus status,ExecResult x,SandboxErrorCode error,Instant started,Instant finished){return new SandboxReceipt(id,r.idempotencyKey(),r.requestDigest(),r.userId(),r.projectId(),r.sessionId(),r.planId(),r.stepId(),r.fence(),r.projectVersion(),r.policyDigest(),"docker-sbx",status,x==null?null:x.exitCode(),x==null?"":x.stdout(),x==null?"":x.stderr(),false,Map.of(),started,finished,error);}
    private String write(Object value){try{return json.writeValueAsString(value);}catch(Exception ex){throw new IllegalStateException(ex);}}
    private String sha256(String value){try{return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}catch(Exception ex){throw new IllegalStateException(ex);}}
    private void requireOk(ExecResult result,String phase){if(result.exitCode()!=0)throw new IllegalStateException(phase+" failed");}
    private void terminate(Process process){process.destroy();try{if(!process.waitFor(2,TimeUnit.SECONDS))process.destroyForcibly();}catch(InterruptedException e){Thread.currentThread().interrupt();process.destroyForcibly();}}
    private record ExecResult(int exitCode,String stdout,String stderr){} private static class OutputLimitException extends Exception{} private static class CancelledException extends Exception{}
}
