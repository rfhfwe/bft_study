package com.pbft;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Timer;
import java.util.TimerTask;
import java.util.Map.Entry;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;	
import org.slf4j.LoggerFactory;

import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Queues;
import com.google.common.collect.Sets;
import com.google.common.util.concurrent.AtomicLongMap;

import static com.pbft.Common.*;

public class HQ {
	
Logger logger = LoggerFactory.getLogger(getClass());
	
	public int size; // 总节点数
	public int maxf; // 最大失效节点

	private volatile boolean isRun = false;
	
	private volatile int num = 0;
	
	private volatile boolean isByzt; // 是否拜占庭节点
	private volatile boolean isHQ; // 是否主节点
	private volatile int credit; // 信用分

	// 消息队列
	private BlockingQueue<HQMsg> qbm = Queues.newLinkedBlockingQueue();

	// 预准备阶段投票信息
	private Set<String> votes_pre = Sets.newConcurrentHashSet();
	
	// 准备阶段投票信息
	private Set<String> votes_pare = Sets.newConcurrentHashSet();
	private AtomicLongMap<String> aggre_pare = AtomicLongMap.create();
	
	// 提交阶段投票信息
	private Set<String> votes_comm = Sets.newConcurrentHashSet();
	private AtomicLongMap<String> aggre_comm = AtomicLongMap.create();

	// 成功处理过的请求
	private Map<String,HQMsg> doneMsg = Maps.newConcurrentMap();

	// 作为主节点受理过的请求
	private Map<String,HQMsg> applyMsg = Maps.newConcurrentMap();
	
	private int index; // 节点标识
	
	private int view; // 视图view
	private volatile boolean viewOk; // 视图状态
	
	// 视图初始化 投票情况
	private AtomicLongMap<Integer> vnumAggreCount = AtomicLongMap.create();
	private Set<String> votes_vnum = Sets.newConcurrentHashSet();
	
	// 请求响应回复情况
	private AtomicLong replyCount = new AtomicLong();
	
	// pbft超时
	private Map<String,Long> timeOuts = Maps.newHashMap();
	
	// 请求超时，view加1，重试
	private Map<String,Long> timeOutsReq = Maps.newHashMap();
	// 发送队列
	private BlockingQueue<HQMsg> reqQueue = Queues.newLinkedBlockingDeque(100);
	// 当前请求
	private HQMsg curMsg;
	
	private volatile AtomicInteger genNo = new AtomicInteger(0); // 序列号生成
	
	private Timer timer;
	
	public HQ(int node,int size, boolean isBytz, boolean isHQ) {
		this.index = node;
		this.size = size;
		this.maxf = (size-1)/3;
		this.isByzt = isBytz;
		this.isHQ = isHQ;
		this.credit = 100;
		timer = new Timer("timer"+node);
	}
	
	public HQ start(){
		new Thread(new Runnable() {
			@Override
			public void run() {
				while (isHQ) {
					try {
						//从消息队列中取出一个消息
						HQMsg msg = qbm.take();
						doAction(msg);
					} catch (InterruptedException e) {
						e.printStackTrace();
					}
				}
				
			}
		}).start();
		
		isRun = true;
		timer.schedule(new TimerTask() {
			int co = 0;
			@Override
			public void run() {
				if(co == 0){
					// 启动后先同步视图
					pubView();
				}
				co++;
				doReq();  // 处理请求
				checkHTimer(); // 超时检测
			}
		}, 10, 100);
		return this;
	}

	protected boolean doAction(HQMsg msg) {
		if(!isRun) {
			return false;
		}
		if(msg != null){
			logger.info("收到消息[" +index+"]:"+ msg);
			switch (msg.getType()) {
				case PP:
					// prepre
					onPrePrepare(msg);
					break;
				case PA:
					// prepare
					onPrepare(msg);
					break;
				case CM:
					// commit
					onCommit(msg);
					break;
				case REQ:
					onReq(msg);
					break;
				case HPP:
					// prepre
					onBack(msg);
					break;
				case HBA:
					// prepare
					onConfirm(msg);
					break;
				case HCON:
					// commit
					onHCommit(msg);
					break;
				case REPLY:
					onReply(msg);
					break;
				case VIEW:
					onGetView(msg);
					break;
				case HREQ:
					onHReq(msg);
					break;
				case CV:
					onChangeView(msg);
					break;
				default:
					break;
			}
			return true;
		}
		return false;
	}

	/**
	 * 真实的发送请求
	 * @return
	 */
	protected boolean doReq() {
		// 上一个请求还没发完/视图初始化中
		if(!viewOk || curMsg != null) {
			return false;
		}
		// 从队列中取出一个请求
		curMsg = reqQueue.poll();
		if(curMsg == null) {
			return false;
		}
		// 设置view
		curMsg.setVnum(this.view);
		// 发送请求
		doSendCurMsg();
		return true;
	}

	/**
	 * 检测超时情况
	 */
	private void checkHTimer() {
		List<String> remo = Lists.newArrayList();
		
		remo.forEach((it)->{
			remove(it);
		});
		
		remo.clear();
		for(Entry<String, Long> item : timeOutsReq.entrySet()){
			if(System.currentTimeMillis() - item.getValue() > 600){
				// 请求超时
				remo.add(item.getKey());
			}
		}
		remo.forEach((data)->{
			logger.info("请求主节点超时["+index+"]:"+data);
			timeOutsReq.remove(data);
			if(curMsg != null && curMsg.getData().equals(data)){

				// 作为客户端发起节点
				vnumAggreCount.incrementAndGet(this.view+1);
				votes_vnum.add(index+"@"+(this.view+1));
				HQMain.publish(curMsg);

			}else{
				// 已经开始选举视图，不用重复发起
				if(!this.viewOk){
					return;
				}
				this.viewOk = false;
				// 作为副本节点，广播视图变更投票
				HQMsg cv = new HQMsg(CV, this.index);
				cv.setVnum(this.view+1);
				HQMain.publish(cv);
			}
			
		});
		
	}
	
	
	private void checkTimer() {
		List<String> remo = Lists.newArrayList();
		for(Entry<String, Long> item : timeOuts.entrySet()){
			if(System.currentTimeMillis() - item.getValue() > 1000){
				// 超时还没达成一致，则本次投票无效
				logger.info("投票无效["+index+"]:"+ item.getKey());
				remo.add(item.getKey());
			}
		}
		remo.forEach((it)->{
			remove(it);
		});
		
		remo.clear();
		for(Entry<String, Long> item : timeOutsReq.entrySet()){
			if(System.currentTimeMillis() - item.getValue() > 600){
				// 请求超时
				remo.add(item.getKey());
			}
		}
		remo.forEach((data)->{
			logger.info("请求主节点超时["+index+"]:"+data);
			timeOutsReq.remove(data);
			if(curMsg != null && curMsg.getData().equals(data)){
				// 作为客户端发起节点
				vnumAggreCount.incrementAndGet(this.view+1);
				votes_vnum.add(index+"@"+(this.view+1));
				HQMain.publish(curMsg);
			}else{
				if(!this.viewOk) return; // 已经开始选举视图，不用重复发起
				this.viewOk = false;
				// 作为副本节点，广播视图变更投票
				PbftMsg cv = new PbftMsg(CV, this.index);
				cv.setVnum(this.view+1);
				PbftMain.publish(cv);
			}
			
		});
		
	}

	void doSendCurMsg(){
		timeOutsReq.put(curMsg.getData(), System.currentTimeMillis());
		HQMain.send(getPriNode(view), curMsg);
	}

	public int getPriNode(int view2){
		return view2%size;
	}

	public void push(HQMsg msg){
		try {
			this.qbm.put(msg);
		} catch (InterruptedException e) {
			e.printStackTrace();
		}
	}

	/**
	 * 初始化视图view
	 */
	public void pubView(){
		HQMsg sed = new HQMsg(VIEW,index);
		HQMain.publish(sed);
	}
	
	/**
	 * 请求入列
	 * @param data
	 * @throws InterruptedException 
	 */
	public void req(String data) throws InterruptedException{
		HQMsg req = new HQMsg(HREQ, this.index);
		req.setData(data);
		reqQueue.put(req);
	}

	// 清理请求相关状态
	private void remove(String it) {
		votes_pre.remove(it);
		votes_pare.removeIf((vp)->{
			return StringUtils.startsWith(vp, it);
		});
		votes_comm.removeIf((vp)->{
			return StringUtils.startsWith(vp, it);
		});
		aggre_pare.remove(it);
		aggre_comm.remove(it);
		timeOuts.remove(it);
	}

	private void onChangeView(HQMsg msg) {
		// 收集视图变更
		String vkey = msg.getNode()+"@"+msg.getVnum();
		if(votes_vnum.contains(vkey)){
			return;
		}
		votes_vnum.add(vkey);
		long count = vnumAggreCount.incrementAndGet(msg.getVnum());
		if(count >= 2*maxf+1){
			vnumAggreCount.clear();
			this.view = msg.getVnum();
			viewOk = true;
			logger.info("视图变更完成["+index+"]："+ view);
			// 可以继续发请求
			if(curMsg != null){
				curMsg.setVnum(this.view);
				logger.info("请求重传["+index+"]："+ curMsg);
				doSendCurMsg();
			}
		}
	}
	
	//第一步 HREQ 主节点广播信息
	private void onHReq(HQMsg msg) {
		if(!msg.isOk()) return;
		HQMsg sed = new HQMsg(msg);
		sed.setNode(index);
		if(msg.getVnum() < view) return;
		if(msg.getVnum() == index){
			if(applyMsg.containsKey(msg.getDataKey())) return; // 已经受理过
			applyMsg.put(msg.getDataKey(), msg);
			// 主节点收到C的请求后进行广播
			sed.setType(HPP);
//			sed.setVnum(view);
			// 主节点生成序列号
			int no = genNo.incrementAndGet();
			sed.setNo(no);
			HQMain.HQpublish(sed);
		}else if(msg.getNode() != index){ // 自身忽略
			// 非主节点收到，说明可能主节点宕机
			if(doneMsg.containsKey(msg.getDataKey())){
				
				// 已经处理过，直接回复
				sed.setType(REPLY);
				HQMain.send(msg.getNode(), sed);
			}else{
				// 认为客户端进行了CV投票
				votes_vnum.add(msg.getNode()+"@"+(msg.getVnum()+1));
				vnumAggreCount.incrementAndGet(msg.getVnum()+1);
				// 未处理，说明可能主节点宕机，转发给主节点试试
				logger.info("转发主节点[" +index+"]:"+ msg);
				HQMain.send(getPriNode(view), sed);
				timeOutsReq.put(msg.getData(), System.currentTimeMillis());
			}
			
			
		}
		
	}
	
	//第二步 Back 回复阶段，各个节点向主节点发送回复信息
	private void onBack(HQMsg msg) {
		if(!checkMsg(msg,true)) return;
		
		String key = msg.getDataKey();
		if(votes_pre.contains(key)){
			// 说明已经发起过，不能重复发起
			return;
		}
		votes_pre.add(key);
		// 启动超时控制
		timeOuts.put(key, System.currentTimeMillis());
		// 移除请求超时，假如有请求的话
		timeOutsReq.remove(msg.getData());
		// 进入准备阶段
		
		if(isByzt) {
			credit = credit/2; 
			String message = msg.getData()+"100";
			msg.setData(message);
			HQMsg sed = new HQMsg(msg);
			sed.setType(HBA);
			sed.setNode(index);
			HQMain.send(getPriNode(view), sed);
			
		}else {
			HQMsg sed = new HQMsg(msg);
			sed.setType(HBA);
			sed.setNode(index);
			HQMain.send(getPriNode(view), sed);
			
		}
		
		

	}

	//第三步骤，主节点回复Confirm信息
	private void onConfirm(HQMsg msg) {
		num++;
		if(!checkMsg(msg,false)) {
			logger.info("异常消息[" +index+"]:"+msg);
			return;
		}
		
		String key = msg.getKey();
		if(votes_pare.contains(key)){
			// 说明已经投过票，不能重复投
			return;
		}
		if(!votes_pre.contains(msg.getDataKey())){
			// 必须先过预准备
			return;
		}
		votes_pare.add(key);
		
		// 票数 +1
		long agCou = aggre_pare.incrementAndGet(msg.getDataKey());
		int hqnum = size - (size-1)/3;
		if(num == hqnum) {
			if(agCou == hqnum){
				aggre_pare.remove(msg.getDataKey());
				// 进入提交阶段
				HQMsg sed = new HQMsg(msg);
				sed.setType(HCON);
				sed.setNode(index);
				doneMsg.put(sed.getDataKey(), sed);
				HQMain.HQpublish(sed);
				
			}else {
				num =0;
				System.out.println("---------------------------------------存在拜占庭节点");
				HQMain.Bstart();
				HQMsg sed = new HQMsg(msg);
				sed.setType(REQ);
				push(sed);
			}
			
		}
		
		// 后续的票数肯定凑不满，超时自动清除
			
	}

	//第四步
	private void onHCommit(HQMsg msg) {
		if(!checkMsg(msg,false)) return;
		// data模拟数据摘要
		String key = msg.getKey();
		
		if(msg.getNode() != index){
			this.genNo.set(msg.getNo());
		}
		// 进入回复阶段
//		if(msg.getOnode() == index){
//			// 自身则直接回复
//			onReply(msg);
//		}else{
//			HQMsg sed = new HQMsg(msg);
//			sed.setType(HCOM);
//			sed.setNode(index);
//			// 回复客户端
////			HQMain.send(sed.getOnode(), sed);
//			doSomething(sed);
//		}
		HQMsg sed = new HQMsg(msg);
		sed.setType(HCOM);
		sed.setNode(index);
		// 回复客户端
//		HQMain.send(sed.getOnode(), sed);
		doSomething(sed);
	}
	
	private void onPrePrepare(HQMsg msg) {
		if(!checkMsg(msg,true)) return;
		
		String key = msg.getDataKey();
		if(votes_pre.contains(key)){
			// 说明已经发起过，不能重复发起
			return;
		}
		votes_pre.add(key);
		// 启动超时控制
		timeOuts.put(key, System.currentTimeMillis());
		// 移除请求超时，假如有请求的话
		timeOutsReq.remove(msg.getData());
		// 进入准备阶段
		HQMsg sed = new HQMsg(msg);
		sed.setType(PA);
		sed.setNode(index);
		HQMain.publish(sed);
	}
	
	private void onPrepare(HQMsg msg) {
		if(!checkMsg(msg,false)) {
			logger.info("异常消息[" +index+"]:"+msg);
			return;
		}
		
		String key = msg.getKey();
		if(votes_pare.contains(key)){
			// 说明已经投过票，不能重复投
			return;
		}
		if(!votes_pre.contains(msg.getDataKey())){
			// 必须先过预准备
			return;
		}
		votes_pare.add(key);
		
		// 票数 +1
		long agCou = aggre_pare.incrementAndGet(msg.getDataKey());
		if(agCou >= 2*maxf+1){
			aggre_pare.remove(msg.getDataKey());
			// 进入提交阶段
			HQMsg sed = new HQMsg(msg);
			sed.setType(CM);
			sed.setNode(index);
			doneMsg.put(sed.getDataKey(), sed);
			HQMain.publish(sed);
		}
		// 后续的票数肯定凑不满，超时自动清除
			
	}
	
	private void onReq(HQMsg msg) {
		if(!msg.isOk()) return;
		HQMsg sed = new HQMsg(msg);
		sed.setNode(index);
		if(msg.getVnum() < view) return;
		if(msg.getVnum() == index){
			if(applyMsg.containsKey(msg.getDataKey())) return; // 已经受理过
			applyMsg.put(msg.getDataKey(), msg);
			// 主节点收到C的请求后进行广播
			sed.setType(PP);
//			sed.setVnum(view);
			// 主节点生成序列号
			int no = genNo.incrementAndGet();
			sed.setNo(no);
			HQMain.publish(sed);
		}else if(msg.getNode() != index){ // 自身忽略
			// 非主节点收到，说明可能主节点宕机
			if(doneMsg.containsKey(msg.getDataKey())){
				// 已经处理过，直接回复
				sed.setType(REPLY);
				HQMain.send(msg.getNode(), sed);
			}else{
				// 认为客户端进行了CV投票
				votes_vnum.add(msg.getNode()+"@"+(msg.getVnum()+1));
				vnumAggreCount.incrementAndGet(msg.getVnum()+1);
				// 未处理，说明可能主节点宕机，转发给主节点试试
				logger.info("转发主节点[" +index+"]:"+ msg);
				HQMain.send(getPriNode(view), sed);
				timeOutsReq.put(msg.getData(), System.currentTimeMillis());
			}
			
			
		}
	}
	
	private void onCommit(HQMsg msg) {
		if(!checkMsg(msg,false)) return;
		// data模拟数据摘要
		String key = msg.getKey();
		if(votes_comm.contains(key)){
			// 说明该节点对该项数据已经投过票，不能重复投
			return;
		}
		if(!votes_pare.contains(key)){
			// 必须先过准备
//			logger.info("---------------------必须先过准备----:"+key);
			return;
		}
		
		votes_comm.add(key);
		// 票数 +1
		long agCou = aggre_comm.incrementAndGet(msg.getDataKey());
		if(agCou >= 2*maxf+1){
			if(credit<100) {
				credit = credit +10;
				if(credit>100) credit =100;
			}
			
			remove(msg.getDataKey());
			if(msg.getNode() != index){
				this.genNo.set(msg.getNo());
			}
			// 进入回复阶段
			if(msg.getOnode() == index){
				// 自身则直接回复
				onReply(msg);
			}else{
				HQMsg sed = new HQMsg(msg);
				sed.setType(REPLY);
				sed.setNode(index);
				// 回复客户端
//				PbftMain.send(sed.getOnode(), sed);
				doSomething(sed);
			}
			
		}
	}

	private void onReply(HQMsg msg) {
		if(curMsg == null || !curMsg.getData().equals(msg.getData()))return;
//		long count = replyCount.incrementAndGet();
//		if(count >= maxf+1){
//			logger.info("消息确认成功[" +index+"]:"+ msg);
			replyCount.set(0);
			curMsg = null; // 当前请求已经完成
			// 执行相关逻辑
			
			doSomething(msg);
//		}
	}

	private void doSomething(HQMsg msg) {
		// 请求被允许，可放心执行
		logger.info("请求执行成功[" +index+"]:"+msg);
	}

	private void onGetView(HQMsg msg) {
		if(msg.getData() == null){
			// 请求
			HQMsg sed = new HQMsg(msg);
			sed.setNode(index);
			sed.setVnum(view);
			sed.setData("initview");
			
			HQMain.send(msg.getNode(), sed);
		}else{
			// 响应
			if(this.viewOk)return;
			long count = vnumAggreCount.incrementAndGet(msg.getVnum());
			if(count >= 2*maxf+1){
				vnumAggreCount.clear();
				this.view = msg.getVnum();
				viewOk = true;
				logger.info("视图初始化完成["+index+"]："+ view);
			}
		}
		
		
	}

	public boolean checkMsg(HQMsg msg,boolean isPre){
		return (msg.isOk() && msg.getVnum() == view 
				// pre阶段校验
				&& (!isPre || msg.getNode() == index || (getPriNode(view) == msg.getNode() && msg.getNo() > genNo.get())));
	}

	public int getView() {
		return view;
	}

	public void setView(int view) {
		this.view = view;
	}
	
	public void close(){
		logger.info("宕机[" +index+"]--------------");
		this.isRun = false;
	}
	
	public void setByzt() {
		logger.info("拜占庭[" +index+"]--------------");
		this.isByzt = true;
	}

	public int getIndex(){
		return this.index;
	}

	public void back() {
		logger.info("恢复[" +index+"]--------------");
		this.isRun = true;
	}
	
	public int getCredit() {
		return this.credit;
	}

	public boolean isHQ() {
		return isHQ;
	}

	public void setHQ(boolean isHQ) {
		this.isHQ = isHQ;
	}

}
