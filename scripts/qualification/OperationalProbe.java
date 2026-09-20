import java.lang.management.*;
import java.net.*;
import java.net.http.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import javax.sql.DataSource;
import com.zaxxer.hikari.HikariDataSource;
import com.zaxxer.hikari.HikariPoolMXBean;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import io.github.piresrenan.orderhub.development.LocalDevelopmentApplication;
import tools.jackson.databind.*;

/** Synthetic release experiment. Run separately after full test verification.
 * Usage: java --class-path <test+runtime cp> OperationalProbe.java [stageSeconds=15] [repeats=2] [warmupSeconds=5]
 * One app replica; disposable PostgreSQL fixture; CLOSED LOOP client in app JVM.
 * No provider production validation, hardware isolation, multi-replica capacity,
 * open-loop queueing, cancellation or definitive saturation claim is implied.
 * Full cycles finish after stage deadline; HTTP requests bounded to 15 seconds.
 * Histograms exact for admitted bounded cycles; expected 422 conflicts NOT errors.
 */
public class OperationalProbe {
 static final ObjectMapper JSON=new ObjectMapper();
 static final HttpClient HTTP=HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(Duration.ofSeconds(5)).build();
 static String app,issuer,tenant,variant,customer,product;
 static volatile String staff,customerToken,platform;
 static HikariPoolMXBean pool;
 static DataSource source; static String ownedContainerId;
 static final AtomicLong received=new AtomicLong(),adjusted=new AtomicLong(),created=new AtomicLong(),invariantFailures=new AtomicLong();
 static final com.sun.management.OperatingSystemMXBean OS=(com.sun.management.OperatingSystemMXBean)ManagementFactory.getOperatingSystemMXBean();
 static record Reply(int status,String body) {}
 static final class Metric {
  final ConcurrentLinkedQueue<Long> nanos=new ConcurrentLinkedQueue<>();
  final ConcurrentHashMap<Integer,LongAdder> statuses=new ConcurrentHashMap<>();
  final LongAdder unexpected=new LongAdder();
  void add(long n,int status,int expected){nanos.add(n);statuses.computeIfAbsent(status,k->new LongAdder()).increment();if(status!=expected)unexpected.increment();}
  Map<String,Object> report(){long[] values=nanos.stream().mapToLong(Long::longValue).sorted().toArray();var m=new LinkedHashMap<String,Object>();m.put("requests",values.length);m.put("p50ms",percentile(values,.50));m.put("p95ms",percentile(values,.95));m.put("p99ms",percentile(values,.99));m.put("maxMs",values.length==0?0:values[values.length-1]/1_000_000.0);m.put("unexpected",unexpected.sum());var h=new TreeMap<Integer,Long>();statuses.forEach((k,v)->h.put(k,v.sum()));m.put("statusHistogram",h);return m;}
 }
 static double percentile(long[] n,double p){return n.length==0?0:n[Math.max(0,(int)Math.ceil(n.length*p)-1)]/1_000_000.0;}
 static void emit(String kind,Object value){System.out.println("OH022 "+kind+" "+JSON.writeValueAsString(value));}
 static Reply request(String method,String path,String token,String selector,String body,String key) throws Exception {
  var b=HttpRequest.newBuilder(URI.create(path)).timeout(Duration.ofSeconds(15));
  if(token!=null)b.header("Authorization","Bearer "+token);
  if(selector!=null)b.header("X-Tenant-Id",selector);
  if(body!=null)b.header("Content-Type","application/json");
  if(key!=null)b.header("Idempotency-Key",key);
  var r=HTTP.send(b.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(body)).build(),HttpResponse.BodyHandlers.ofString());
  return new Reply(r.statusCode(),r.body());
 }
 static Reply measured(Map<String,Metric> m,String label,int expected,String method,String path,String token,String selector,String body,String key){
  long start=System.nanoTime();Reply r;
  try{r=request(method,app+path,token,selector,body,key);}catch(Exception e){r=new Reply(0,"");if(e instanceof InterruptedException)Thread.currentThread().interrupt();}
  m.computeIfAbsent(label,k->new Metric()).add(System.nanoTime()-start,r.status,expected);return r;
 }
 static void require(Reply r,int status,String label){if(r.status!=status)throw new IllegalStateException(label+" unexpected status "+r.status);}
 static String token(String persona)throws Exception{var r=request("POST",issuer+"/tokens/"+persona,null,null,"",null);require(r,200,"token issuance");return JSON.readTree(r.body).get("access_token").asText();}
 static void refresh()throws Exception{staff=token("staff");customerToken=token("customer");platform=token("platform");}
 static String movement(String key,int quantity,boolean adjustment){return "{\"operationId\":\""+key+"\",\"variantId\":\""+variant+"\",\""+(adjustment?"delta":"quantity")+"\":"+quantity+",\"reason\":\"QUALIFICATION\"}";}
 static String order(int quantity){return "{\"customerId\":\""+customer+"\",\"items\":[{\"variantId\":\""+variant+"\",\"quantity\":"+quantity+"}]}";}
 static void same(Reply a,Reply b){if(a.status==201&&b.status==201&&!JSON.readTree(a.body).equals(JSON.readTree(b.body)))invariantFailures.incrementAndGet();}
 static void cycle(Map<String,Metric> metrics){
  measured(metrics,"catalogRead",200,"GET","/catalog/products/"+product,staff,tenant,null,null);
  measured(metrics,"inventoryRead",200,"GET","/inventory/positions/"+variant,staff,tenant,null,null);
  // Actual admitted administrative read. There is no GET /platform/tenants endpoint.
  measured(metrics,"platformOrganizationRead",200,"GET","/platform/organizations",platform,null,null,null);
  String receipt=movement(UUID.randomUUID().toString(),2,false);
  var r=measured(metrics,"receipt",201,"POST","/inventory/receipts",staff,tenant,receipt,null);if(r.status==201)received.addAndGet(2);
  var rr=measured(metrics,"receiptReplay",201,"POST","/inventory/receipts",staff,tenant,receipt,null);same(r,rr);
  String adjust=movement(UUID.randomUUID().toString(),-1,true);
  var a=measured(metrics,"adjustment",201,"POST","/inventory/adjustments",staff,tenant,adjust,null);if(a.status==201)adjusted.decrementAndGet();
  var ar=measured(metrics,"adjustmentReplay",201,"POST","/inventory/adjustments",staff,tenant,adjust,null);same(a,ar);
  String key="qualification-"+UUID.randomUUID();
  var o=measured(metrics,"orderCreate",201,"POST","/orders",customerToken,tenant,order(1),key);if(o.status==201)created.incrementAndGet();
  var replay=measured(metrics,"orderReplay",201,"POST","/orders",customerToken,tenant,order(1),key);same(o,replay);
  // If initial request failed, this can establish a different outcome; report as error and correctness uncertainty.
  measured(metrics,"orderFingerprintConflict",422,"POST","/orders",customerToken,tenant,order(2),key);
 }
 static long gcCount(){return ManagementFactory.getGarbageCollectorMXBeans().stream().mapToLong(x->Math.max(0,x.getCollectionCount())).sum();}
 static long gcMillis(){return ManagementFactory.getGarbageCollectorMXBeans().stream().mapToLong(x->Math.max(0,x.getCollectionTime())).sum();}
 static Map<String,Object> dbSnapshot(){var result=new LinkedHashMap<String,Object>();try(var c=source.getConnection();var s=c.createStatement()){
  s.setQueryTimeout(3);
  try(var r=s.executeQuery("SELECT count(*) FILTER (WHERE state='active'), count(*) FILTER (WHERE wait_event_type='Lock'), count(*) FROM pg_stat_activity WHERE datname=current_database()")){r.next();result.put("active",r.getLong(1));result.put("lockWaiting",r.getLong(2));result.put("connections",r.getLong(3));}
  try(var r=s.executeQuery("SELECT deadlocks, xact_commit, xact_rollback FROM pg_stat_database WHERE datname=current_database()")){r.next();result.put("deadlocks",r.getLong(1));result.put("commits",r.getLong(2));result.put("rollbacks",r.getLong(3));}
 }catch(Exception e){result.put("unavailable",true);}return result;}
 static void stage(String name,int concurrency,int seconds)throws Exception{
  refresh();var metrics=new ConcurrentHashMap<String,Metric>();
  var dbBefore=dbSnapshot();long cpu=OS.getProcessCpuTime(),gc=gcCount(),gct=gcMillis(),start=System.nanoTime();
  long deadline=start+TimeUnit.SECONDS.toNanos(seconds);var sequence=new AtomicInteger();
  var dockerSamples=new ConcurrentLinkedQueue<Map<String,Object>>();var dockerMonitor=Executors.newSingleThreadScheduledExecutor();dockerMonitor.scheduleWithFixedDelay(()->dockerSamples.add(dockerStats()),0,3,TimeUnit.SECONDS);
  var peaks=new AtomicLongArray(5);var monitor=Executors.newSingleThreadScheduledExecutor();
  monitor.scheduleAtFixedRate(()->{long[] v={pool.getActiveConnections(),pool.getIdleConnections(),pool.getThreadsAwaitingConnection(),ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed(),ManagementFactory.getMemoryMXBean().getNonHeapMemoryUsage().getUsed()};for(int i=0;i<v.length;i++){final long x=v[i];peaks.accumulateAndGet(i,x,Math::max);}},0,20,TimeUnit.MILLISECONDS);
  var workers=Executors.newFixedThreadPool(concurrency);var futures=new ArrayList<Future<?>>();
  try{for(int i=0;i<concurrency;i++)futures.add(workers.submit(()->{while(System.nanoTime()<deadline&&!Thread.currentThread().isInterrupted()&&sequence.getAndIncrement()<25000)cycle(metrics);}));for(var f:futures)f.get();}
  finally{workers.shutdownNow();monitor.shutdownNow();dockerMonitor.shutdownNow();dockerMonitor.awaitTermination(10,TimeUnit.SECONDS);}
  double elapsed=(System.nanoTime()-start)/1e9;var report=new LinkedHashMap<String,Object>();
  report.put("databaseContainerSamples",dockerSamples);report.put("name",name);report.put("concurrency",concurrency);report.put("targetSeconds",seconds);report.put("elapsedSeconds",elapsed);report.put("cpuSeconds",(OS.getProcessCpuTime()-cpu)/1e9);report.put("processCpuOneCorePercent",100*(OS.getProcessCpuTime()-cpu)/(elapsed*1e9));report.put("gcCount",gcCount()-gc);report.put("gcMillis",gcMillis()-gct);
  report.put("poolPeakActive",peaks.get(0));report.put("poolPeakIdle",peaks.get(1));report.put("poolPeakPending",peaks.get(2));report.put("heapPeakBytes",peaks.get(3));report.put("nonHeapPeakBytes",peaks.get(4));report.put("dbBefore",dbBefore);report.put("dbAfter",dbSnapshot());
  long requests=metrics.values().stream().mapToLong(x->x.nanos.size()).sum(),unexpected=metrics.values().stream().mapToLong(x->x.unexpected.sum()).sum();
  report.put("requests",requests);report.put("requestsPerSecond",requests/elapsed);report.put("unexpectedStatusRate",requests==0?0:unexpected/(double)requests);report.put("cycleCapReached",sequence.get()>=25000);
  var aggregate=new Metric();metrics.values().forEach(x->{aggregate.nanos.addAll(x.nanos);aggregate.unexpected.add(x.unexpected.sum());x.statuses.forEach((k,v)->aggregate.statuses.computeIfAbsent(k,z->new LongAdder()).add(v.sum()));});report.put("aggregateLatency",aggregate.report());var operations=new TreeMap<String,Object>();metrics.forEach((k,v)->operations.put(k,v.report()));report.put("operations",operations);emit("stage",report);
 }
 static Map<String,Object> dockerStats(){
  try{String raw=docker("stats","--no-stream","--format","{{json .}}",ownedContainerId);var n=JSON.readTree(raw);return Map.of("cpuPercent",n.path("CPUPerc").asText(),"memoryUsage",n.path("MemUsage").asText(),"memoryPercent",n.path("MemPerc").asText(),"pids",n.path("PIDs").asText());}
  catch(Exception e){return Map.of("unavailable",true);}
 }
 static String docker(String... arguments)throws Exception{
  if(ownedContainerId==null||!ownedContainerId.matches("[0-9a-f]{12,64}"))throw new IllegalStateException("No validated owned container");
  var cmd=new ArrayList<String>();cmd.add("docker");cmd.addAll(Arrays.asList(arguments));
  var p=new ProcessBuilder(cmd).redirectError(ProcessBuilder.Redirect.DISCARD).start();
  try{if(!p.waitFor(15,TimeUnit.SECONDS)){p.destroyForcibly();throw new IllegalStateException("Owned Docker command timeout");}if(p.exitValue()!=0)throw new IllegalStateException("Owned Docker command failed");return new String(p.getInputStream().readAllBytes(),java.nio.charset.StandardCharsets.UTF_8).trim();}
  finally{if(p.isAlive())p.destroyForcibly();}
 }
 static int faultRequest(String path,boolean authenticated){
  try{var b=HttpRequest.newBuilder(URI.create(app+path)).timeout(Duration.ofSeconds(45));if(authenticated)b.header("Authorization","Bearer "+staff).header("X-Tenant-Id",tenant);return HTTP.send(b.GET().build(),HttpResponse.BodyHandlers.discarding()).statusCode();}
  catch(Exception e){if(e instanceof InterruptedException)Thread.currentThread().interrupt();return 0;}
 }
 static void awaitRecovered()throws Exception{
  long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(90);while(System.nanoTime()<deadline){if(faultRequest("/readyz",false)==200&&faultRequest("/inventory/positions/"+variant,true)==200)return;Thread.sleep(500);}throw new IllegalStateException("Recovery deadline exceeded");
 }
 static void faults()throws Exception{
  refresh();var before=position();var held=new ArrayList<java.sql.Connection>();var executor=Executors.newSingleThreadExecutor();
  int status;long start=0;int pending=0;
  try{
   int maximum=source.unwrap(HikariDataSource.class).getMaximumPoolSize();for(int i=0;i<maximum;i++)held.add(source.getConnection());
   start=System.nanoTime();var request=executor.submit(()->faultRequest("/inventory/positions/"+variant,true));
   long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(48);while(!request.isDone()&&System.nanoTime()<deadline){pending=Math.max(pending,pool.getThreadsAwaitingConnection());Thread.sleep(20);}
   status=request.get(2,TimeUnit.SECONDS);
   emit("poolExhaustion",Map.of("heldConnections",held.size(),"poolPeakPending",pending,"httpStatus",status,"elapsedSeconds",(System.nanoTime()-start)/1e9,"serverFailureObserved",status>=500,"transportTimeout",status==0,"livenessStatus",faultRequest("/livez",false)));
  }finally{for(var c:held)try{c.close();}catch(Exception ignored){}executor.shutdownNow();}
  awaitRecovered();var afterPool=position();boolean poolUnchanged=before.equals(afterPool);emit("poolRecovery",Map.of("ready",true,"positionUnchanged",poolUnchanged));
  // Revoke only the synthetic fixture login while retaining one recovery
  // connection. Docker stop/start changes ephemeral published ports and would
  // test stale fixture addressing rather than application database recovery.
  long outageStart=System.nanoTime();int outageReady=0,outageLive=0,outageRead=0;
  try(var recovery=source.getConnection();var statement=recovery.createStatement()){
   try{
    statement.execute("ALTER ROLE orderhub_development NOLOGIN");
    statement.execute("SELECT pg_terminate_backend(pid) FROM pg_stat_activity WHERE usename='orderhub_development' AND pid <> pg_backend_pid()");
    outageLive=faultRequest("/livez",false);outageReady=faultRequest("/readyz",false);outageRead=faultRequest("/inventory/positions/"+variant,true);
   }finally{statement.execute("ALTER ROLE orderhub_development LOGIN");}
  }
  emit("databaseOutage",Map.of("livenessStatus",outageLive,"readinessStatus",outageReady,"businessReadStatus",outageRead,"elapsedSeconds",(System.nanoTime()-outageStart)/1e9));
  long recoveryStart=System.nanoTime();awaitRecovered();var afterDb=position();boolean dbUnchanged=before.equals(afterDb);
  emit("databaseRecovery",Map.of("ready",true,"positionUnchanged",dbUnchanged,"elapsedSeconds",(System.nanoTime()-recoveryStart)/1e9));
  if(!poolUnchanged||!dbUnchanged)throw new IllegalStateException("Read-only fault experiment changed position");
  if(status<500||outageReady!=503||outageLive!=200||outageRead<500)throw new IllegalStateException("Fault qualification did not prove bounded server failure and correct probe behavior");
 }
 static JsonNode position()throws Exception{var r=request("GET",app+"/inventory/positions/"+variant,staff,tenant,null,null);require(r,200,"position");return JSON.readTree(r.body);}
 public static void main(String[] args)throws Exception{
  int seconds=args.length>0?Integer.parseInt(args[0]):15,repeats=args.length>1?Integer.parseInt(args[1]):2,warmup=args.length>2?Integer.parseInt(args[2]):5;
  if(seconds<1||seconds>120||repeats<1||repeats>5||warmup<1||warmup>30)throw new IllegalArgumentException("Bounded arguments required");
  emit("environment",Map.of("os",System.getProperty("os.name"),"osVersion",System.getProperty("os.version"),"java",System.getProperty("java.runtime.version"),"cpus",Runtime.getRuntime().availableProcessors(),"jvmMaxHeap",Runtime.getRuntime().maxMemory(),"replicas",1,"stageSeconds",seconds,"repeats",repeats,"warmupSeconds",warmup));
  try(var context=LocalDevelopmentApplication.start(0,0)){
   app="http://127.0.0.1:"+((WebServerApplicationContext)context).getWebServer().getPort();var bean=context.getBean("developmentIssuer");issuer=(String)bean.getClass().getMethod("baseUri").invoke(bean);
   var dbBean=(org.testcontainers.postgresql.PostgreSQLContainer)context.getBean("developmentDatabase");ownedContainerId=dbBean.getContainerId();if(ownedContainerId==null||!ownedContainerId.matches("[0-9a-f]{12,64}"))throw new IllegalStateException("Owned fixture container identity unavailable");
   source=context.getBean(DataSource.class);var hikari=source.unwrap(HikariDataSource.class);pool=hikari.getHikariPoolMXBean();
   var fixture=request("GET",issuer+"/fixture",null,null,null,null);require(fixture,200,"fixture");var f=JSON.readTree(fixture.body);tenant=f.get("tenantId").asText();variant=f.get("variantId").asText();customer=f.get("customerId").asText();product=f.get("productId").asText();refresh();
   emit("runtime",Map.of("poolMaximum",hikari.getMaximumPoolSize(),"poolAcquisitionTimeoutMs",hikari.getConnectionTimeout(),"postgres","18.6 pinned fixture","dataset","one synthetic tenant/product/variant/customer; one contended inventory position","distribution","10 requests/cycle: 3 reads, 2 movement writes + 2 replays, 1 order + replay + expected conflict","requestTimeoutSeconds",15));
   int live=request("GET",app+"/livez",null,null,null,null).status,ready=request("GET",app+"/readyz",null,null,null,null).status;
   var preflight=HTTP.send(HttpRequest.newBuilder(URI.create(app+"/orders")).timeout(Duration.ofSeconds(15)).header("Origin","https://qualification.example").header("Access-Control-Request-Method","POST").header("Access-Control-Request-Headers","authorization,x-tenant-id,idempotency-key,content-type").method("OPTIONS",HttpRequest.BodyPublishers.noBody()).build(),HttpResponse.BodyHandlers.discarding());
   emit("discovery",Map.of("livenessStatus",live,"readinessStatus",ready,"preflightStatus",preflight.statusCode(),"preflightHasAllowedOrigin",preflight.headers().firstValue("Access-Control-Allow-Origin").isPresent()));
   var replenish=request("POST",app+"/inventory/receipts",staff,tenant,movement(UUID.randomUUID().toString(),1_000_000,false),null);require(replenish,201,"replenishment");var initial=position();
   stage("warmup",1,warmup);for(int repeat=1;repeat<=repeats;repeat++){for(int concurrency:new int[]{1,4,16,64})stage("repeat-"+repeat,concurrency,seconds);stage("recovery-"+repeat,1,seconds);}
   faults();
   refresh();var end=position();long expectedOnHand=initial.get("onHand").asLong()+received.get()+adjusted.get(),expectedCommitted=initial.get("committed").asLong()+created.get();boolean stockOk=end.get("onHand").asLong()==expectedOnHand&&end.get("committed").asLong()==expectedCommitted;
   emit("correctness",Map.of("onHandExpected",expectedOnHand,"onHandActual",end.get("onHand").asLong(),"committedExpected",expectedCommitted,"committedActual",end.get("committed").asLong(),"successfulUniqueOrders",created.get(),"replayRepresentationMismatches",invariantFailures.get(),"stockMatchesObservedSuccessfulEffects",stockOk,"readinessFinal",request("GET",app+"/readyz",null,null,null,null).status));
   emit("limitations",List.of("Not production capacity: co-located closed-loop load client and app JVM share CPU/memory/GC; database is Docker fixture without imposed resource limits.","Single hot SKU and tiny dataset; growing Orders/evidence; repeat stages are not identical fresh datasets.","Pool sampled every 20ms; DB statistics only before/after stages can miss lock wait peaks and async statistics lag.","Docker stats samples are database container CPU and memory usage, not PostgreSQL RSS; no disk/network, external Cognito/JWK latency, true provider logout, multiple replicas or full journeys. Controlled pool exhaustion and owned database login outage are separate recovery experiments.","Expected conflict 422 is excluded from unexpected errors; status 0 denotes transport failure. An uncertain first write followed by successful replay may make observed-effect reconciliation fail; investigate durable state rather than declaring corruption.","No claim of saturation unless measured degradation/error/pending evidence supports it. Counts/histograms include stage drain; maximum 25000 cycles per stage protects harness memory."));
   if(!stockOk||invariantFailures.get()!=0)throw new IllegalStateException("Correctness evidence requires investigation");
  }
 }
}


