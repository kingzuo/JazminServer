/**
 * 
 */
package jazmin.driver.mq;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.ConcurrentHashMap;

import jazmin.core.Driver;
import jazmin.core.Jazmin;
import jazmin.core.Registerable;
import jazmin.core.monitor.Monitor;
import jazmin.core.monitor.MonitorAgent;
import jazmin.core.thread.Dispatcher;
import jazmin.driver.mq.file.FileTopicQueue;
import jazmin.driver.mq.memory.MemoryTopicQueue;
import jazmin.log.Logger;
import jazmin.log.LoggerFactory;
import jazmin.misc.InfoBuilder;
import jazmin.server.console.ConsoleServer;

/**
 * @author yama
 *
 */
public class MessageQueueDriver extends Driver implements Registerable{
	//
	private static Logger logger=LoggerFactory.get(MessageQueueDriver.class);
	//
	public static final String TOPIC_QUEUE_TYPE_MEMORY="memory";
	public static final String TOPIC_QUEUE_TYPE_FILE="file";
	//
	public static Message takeNext=new Message();
	//
	Map<String,TopicQueue>topicQueues;
	Map<String,TopicSubscriber>subscribers;
	Thread takeMessageThread;
	boolean stopTakeThread=false;
	String workDir;
	//
	private Object lockObject=new Object();
	//
	public MessageQueueDriver() {
		topicQueues=new ConcurrentHashMap<>();
		subscribers=new ConcurrentHashMap<>();
		workDir="./jazmin_mq_work";
	}

	//
	public void createTopic(String name,String type){
		if(topicQueues.containsKey(name)){
			throw new IllegalArgumentException("topic queue already exists with name "+name);
		}
		if(type.equals(TOPIC_QUEUE_TYPE_MEMORY)){
			topicQueues.put(name,new MemoryTopicQueue(name));
			return;
		}
		if(type.equals(TOPIC_QUEUE_TYPE_FILE)){
			FileTopicQueue queue=new FileTopicQueue(name);
			topicQueues.put(name,queue);
			queue.setWorkDir(workDir);
			return;
		}
		throw new IllegalArgumentException("bad topic type:"+type);
	}
	/**
	 * @return the workDir
	 */
	public String getWorkDir() {
		return workDir;
	}

	/**
	 * @param workDir the workDir to set
	 */
	public void setWorkDir(String workDir) {
		this.workDir = workDir;
	}

	//
	List<TopicQueue>getTopicQueues(){
		return new ArrayList<TopicQueue>(topicQueues.values());
	}
	//
	//
	TopicQueue getTopicQueue(String topic){
		return topicQueues.get(topic);
	}
	//
	List<TopicSubscriber>getTopicSubscribers(){
		return new ArrayList<TopicSubscriber>(subscribers.values());
	}
	//
	@Override
	public void register(Object object) {
		if(isStarted()){
			throw new IllegalStateException("register before started.");
		}
		for(Method m:object.getClass().getDeclaredMethods()){
			if(!m.isAnnotationPresent(TopicSubscriberDefine.class)){
				continue;
			}
			if(!Modifier.isPublic(m.getModifiers())){
				throw new IllegalArgumentException("subscriber method shoule be public");
			}
			if(m.getParameterCount()!=1){
				throw new IllegalArgumentException("subscriber method parameter must be MessageEvent");
			}
			if(!m.getParameters()[0].getType().equals(MessageEvent.class)){
				throw new IllegalArgumentException("subscriber method parameter must be MessageEvent");
			}
			TopicSubscriberDefine td= m.getAnnotation(TopicSubscriberDefine.class);
			//
			if(!topicQueues.containsKey(td.topic())){
				throw new IllegalArgumentException("can not find topic queue with name:"+td.topic());
			}
			//
			if(td.name()<=0){
				throw new IllegalArgumentException("subscriber name must > 0");
			}
			//
			TopicQueue queue=topicQueues.get(td.topic());
			//
			TopicSubscriber l=new TopicSubscriber();
			l.id=td.name();
			l.method=m;
			l.topic=td.topic();
			l.type=td.type();
			l.instance=object;
			if(subscribers.containsKey(l.topic+"-"+l.id)){
				throw new IllegalArgumentException("subscriber already exists with name:"+l.id+" on topic:"+l.topic);
			}
			subscribers.put(l.topic+"-"+l.id, l);
			queue.subscribe(l.id);
			logger.info("resister subscribe {} topic:{}",l.id,l.topic);
		}
	}
	//
	//
	private TopicQueue getQueue(String topic){
		TopicQueue queue=topicQueues.get(topic);
		if(queue==null){
			throw new IllegalArgumentException("can not find topic queue:"+topic);
		}
		return queue;
	}
	//
	public void publish(String topic,Object payload){
		getQueue(topic).publish(payload);
		notifyTake();
	}
	//
	public void accept(String topic,String messageId){
		getQueue(topic).accept(messageId);
		notifyTake();
	}
	//
	public void reject(String topic,String messageId){
		getQueue(topic).reject(messageId);
		notifyTake();
	}
	//
	private void notifyTake(){
		synchronized (lockObject) {
			lockObject.notifyAll();
		}
	}
	//
	private void waitTake(){
		synchronized (lockObject) {
			try {
				lockObject.wait(1000*10);//wait for 10 seconds
			} catch (InterruptedException e) {
				logger.catching(e);
			}
		}
	}
	/**
	 * pull message
	 * @return
	 */
	public Message pullMessage(String topic,short subscriber){
		Message msg=null;
		TopicQueue queue=topicQueues.get(topic);
		if(queue==null){
			throw new IllegalArgumentException("can not find topic queue:"+topic);
		}
		TopicSubscriber sub=subscribers.get(topic+"-"+subscriber);
		if(sub.type!=TopicSubscriberType.pull){
			throw new IllegalArgumentException("only pull subscriber can perform this action");
		}
		
		return msg;
	}
	//
	private void takeMessage(){
		while(!stopTakeThread){
			int messageCount=0;
			for(Entry<String,TopicQueue>e : topicQueues.entrySet()){
				Message message=e.getValue().take();
				if(message!=null){
					messageCount++;
					if(message!=takeNext){
						try{
							sendMessage(e.getKey(),message);
						}catch (Exception ee) {
							logger.catching(ee);
						}
					}
				}
			}
			//
			if(messageCount==0){
				waitTake();
			}
		}
	}
	//
	private void sendMessage(String topic,Message message){
		TopicSubscriber subscriber=subscribers.get(topic+"-"+message.subscriber);
		if(subscriber==null){
			logger.warn("drop message {} {} ",topic+"-"+message.subscriber,message.id);
			accept(topic, message.id);
			return;
		}
		subscriber.sentCount++;
		subscriber.lastSentTime=System.currentTimeMillis();
		MessageEvent event=new MessageEvent();
		if(subscriber.type==TopicSubscriberType.push){
			//push mode
			event.message=message;
		}
		event.messageQueueDriver=this;
		Jazmin.dispatcher.invokeInPool(
				"MessageQueueDriver",
				subscriber.instance,
				subscriber.method, Dispatcher.EMPTY_CALLBACK,
				event);
	}
	//
	private void checkSet(){
		while(true){
			try {
				Thread.sleep(1000*10);
				for(Entry<String,TopicQueue>e : topicQueues.entrySet()){
					e.getValue().checkSet();
				}
			} catch (Exception e1) {
				logger.catching(e1);
			}	
		}
	}
	//--------------------------------------------------------------------------
	@Override
	public void start() throws Exception {
		super.start();
		takeMessageThread=new Thread(this::takeMessage);
		takeMessageThread.setName("MessageQueueDriverTakeThread");
		takeMessageThread.start();
		//
		Thread checkSetThread=new Thread(this::checkSet);
		checkSetThread.setName("MessageQueueDriverCheckThread");
		checkSetThread.start();
		//
		topicQueues.forEach((k,v)->{
			v.start();
		});
		//
		ConsoleServer cs=Jazmin.getServer(ConsoleServer.class);
		if(cs!=null){
			cs.registerCommand(MessageQueueDriverCommand.class);
		}
		Jazmin.mointor.registerAgent(new MessageQueueDriverMonitorAgent());
	}
	//
	@Override
	public void stop() throws Exception {
		super.stop();
		stopTakeThread=true;
	}
	//
	public String info() {
		InfoBuilder ib=InfoBuilder.create();
		ib.section("info").format("%-30s:%-30s\n");
		ib.print("workDir",workDir);
		
		ib.section("topicQueues").format("%-30s:%-30s\n");
		topicQueues.forEach((k,v)->{
			ib.print(k,v.getId()+"["+v.getType()+"]");
		});
		ib.section("subscribers").format("%-30s subscribe topic: %-30s\n");
		subscribers.forEach((k,v)->{
			ib.print(k,v.topic);
		});
		return ib.toString();
	}
	//
	//--------------------------------------------------------------------------
	//
	private class MessageQueueDriverMonitorAgent implements MonitorAgent{
		@Override
		public void sample(int idx,Monitor monitor) {
			for(TopicQueue queue :getTopicQueues()){
				Map<String,String>info1=new HashMap<String, String>();
				info1.put("publishCount", queue.getPublishCount()+"");
				monitor.sample("MessageQueueDriver.PublishCount."+queue.id,
								Monitor.CATEGORY_TYPE_COUNT,info1);
			}
			for(TopicQueue queue :getTopicQueues()){
				Map<String,String>info1=new HashMap<String, String>();
				info1.put("queueLength", queue.length()+"");
				monitor.sample("MessageQueueDriver.QueueLength."+queue.id,
								Monitor.CATEGORY_TYPE_COUNT,info1);
			}
		}
		//
		@Override
		public void start(Monitor monitor) {
			Map<String,String>info=new HashMap<String, String>();
			for(TopicQueue q:getTopicQueues()){
				info.put("TopicQueue-"+q.id, q.getType());
			}
			monitor.sample("MessageQueueDriver.Info",Monitor.CATEGORY_TYPE_KV,info);
		}
	}
	
}