# Job类

```
private JobState state = JobState.DEFINE;
private JobStatus status;
private long statustime;
private Cluster cluster;
private ReservationId reservationId;
```

## waitForCompletion()函数

enum
JobState.DEFINE / JobState.RUNNING


```
public boolean waitForCompletion(boolean verbose) 
	throws IOException, InterruptedException, ClassNotFoundException {
    if (state == JobState.DEFINE) {
        submit();
    }
    if (verbose) {
        monitorAndPrintJob();
    } else {
        // get the completion poll interval from the client.
        int completionPollIntervalMillis =
            Job.getCompletionPollInterval(cluster.getConf());
        while (!isComplete()) {
            try {
                Thread.sleep(completionPollIntervalMillis);
            } catch (InterruptedException ie) {
            }
        }
    }
    return isSuccessful();
}
```

## submit()函数

JobSubmitter submitter

state = JobState.DEFINE ==> JobState.RUNNING

1. ensureState(JobState.DEFINE)。确保当前任务处于准备阶段。
2. setUseNewAPI()。启用新版本的API，即使用org.apache.hadoop.mapreduce下的Mapper和Reducer。
3. connect()。连接MapReduce集群，connect()为Job.cluster变量赋值。
4. 创建一个JobSubmitter对象。
5. 执行submitter.submitJobInternal()方法，提交任务。
6. state = JobState.RUNNING。设置job状态开始运行。


```
public void submit()
    throws IOException, InterruptedException, ClassNotFoundException {
    ensureState(JobState.DEFINE);

	// 设置使用新版API
    setUseNewAPI();
	
    connect();

    final JobSubmitter submitter =
        getJobSubmitter(cluster.getFileSystem(), cluster.getClient());
    status = ugi.doAs(new PrivilegedExceptionAction<JobStatus>() {
        public JobStatus run() throws IOException, InterruptedException,
            ClassNotFoundException {
            return submitter.submitJobInternal(Job.this, cluster);
        }
    });
    state = JobState.RUNNING;
    LOG.info("The url to track the job: " + getTrackingURL());
}
```

## connect()函数

当cluster为空时，根据Configuration内容创建一个新的Cluster对象。

主要是为Cluster中的Client赋值，ClientProtocolProvider Client即为提交器，分为本地提交器和YARN提交器，由配置文件决定具体选择。

```
private synchronized void connect()
    throws IOException, InterruptedException, ClassNotFoundException {
    if (cluster == null) {
        cluster =
        ugi.doAs(new PrivilegedExceptionAction<Cluster>() {
            public Cluster run()
            throws IOException, InterruptedException,
                ClassNotFoundException {
                return new Cluster(getConfiguration());
            }
        });
    }
}
```
