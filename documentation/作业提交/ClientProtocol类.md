# ClientProtocol类

ClientProtocol有两个派生类LocalJobRunner和YARNRunner。

```
// 提交并开始执行job
public JobStatus submitJob(JobID jobId, String jobSubmitDir, Credentials ts)
	throws IOException, InterruptedException;
```

## LocalJobRunner类

## YARNRunner类



