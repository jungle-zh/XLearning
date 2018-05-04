package net.qihoo.xlearning.AM;

import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.AmazonS3ClientBuilder;
import com.amazonaws.services.s3.model.ListObjectsV2Result;
import com.amazonaws.services.s3.model.S3ObjectSummary;
import com.google.gson.Gson;
import net.qihoo.xlearning.api.ApplicationContext;
import net.qihoo.xlearning.api.XLearningConstants;
import net.qihoo.xlearning.common.*;
import net.qihoo.xlearning.conf.XLearningConfiguration;
import net.qihoo.xlearning.container.XLearningContainer;
import net.qihoo.xlearning.container.XLearningContainerId;
import net.qihoo.xlearning.util.Utilities;
import net.qihoo.xlearning.webapp.AMParams;
import org.apache.commons.lang.StringUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.*;
import org.apache.hadoop.fs.permission.FsPermission;
import org.apache.hadoop.mapred.*;
import org.apache.hadoop.util.ReflectionUtils;
import org.apache.hadoop.service.CompositeService;
import org.apache.hadoop.yarn.api.ApplicationConstants;
import org.apache.hadoop.yarn.api.records.*;
import org.apache.hadoop.yarn.client.api.AMRMClient.ContainerRequest;
import org.apache.hadoop.yarn.client.api.async.AMRMClientAsync;
import org.apache.hadoop.yarn.client.api.async.NMClientAsync;
import org.apache.hadoop.yarn.conf.YarnConfiguration;
import org.apache.hadoop.yarn.util.ConverterUtils;
import org.apache.hadoop.yarn.util.Records;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.math.RoundingMode;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.security.NoSuchAlgorithmException;
import java.text.DecimalFormat;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.LinkedBlockingQueue;

public class ApplicationMaster extends CompositeService {

  private static final Log LOG = LogFactory.getLog(ApplicationMaster.class);
  private final Configuration conf;
  private Map<String, String> envs;
  private AMRMClientAsync<ContainerRequest> amrmAsync;
  private NMClientAsync nmAsync;
  private ApplicationAttemptId applicationAttemptID;
  private String applicationMasterHostname;
  private String applicationMasterTrackingUrl;
  private String applicationHistoryUrl;
  private int workerMemory;
  private int workerVCores;
  private int workerNum;
  private int psMemory;
  private int psVCores;
  private int psNum;
  private Boolean single;
  private Boolean singleMx;
  private int appPriority;
  // location of AppMaster.jar on HDFS
  private Path appJarRemoteLocation;
  // location of job.xml on HDFS
  private Path appConfRemoteLocation;
  // location of files on HDFS
  private String appFilesRemoteLocation;
  // location of lib jars on HDFS
  private String appLibJarsRemoteLocation;
  // location of cacheFiles on HDFS
  private String appCacheFilesRemoteLocation;
  // location of cacheArchive on HDFS
  private String appCacheArchivesRemoteLocation;
  private String xlearningCommand;
  private String dmlcPsRootUri;
  private int dmlcPsRootPort;
  private String dmlcTrackerUri;
  private int dmlcTrackerPort;
  private String xlearningAppType;
  private List<Container> acquiredWorkerContainers;
  private List<Container> acquiredPsContainers;
  private final LinkedBlockingQueue<Message> applicationMessageQueue;
  private final List<OutputInfo> outputInfos;
  private ConcurrentHashMap<String, List<FileStatus>> input2FileStatus;
  //private S3InputInfo s3InputInfo;
  private ConcurrentHashMap<XLearningContainerId, S3InputInfo> containerId2S3InputInfo;
  private List<S3ObjectEntry> s3ObjectEntrys;
  private ConcurrentHashMap<XLearningContainerId, List<InputInfo>> containerId2InputInfo;
  private InputSplit[] inputFileSplits;
  private ConcurrentHashMap<XLearningContainerId, List<InputSplit>> containerId2InputSplit;
  // An RPC Service listening the container status
  private ApplicationContainerListener containerListener;
  private int statusUpdateInterval;
  private final ApplicationContext applicationContext;
  private RMCallbackHandler rmCallbackHandler;
  private ContainerRequest workerContainerRequest;
  private ContainerRequest psContainerRequest;
  private Map<String, LocalResource> containerLocalResource;
  private ApplicationWebService webService;
  private ApplicationMessageService messageService;

  private Boolean startSavingModel;
  private Boolean lastSavingStatus;
  private List<Long> savingModelList;

  private Thread cleanApplication;

  /**
   * Constructor, connect to Resource Manager
   *
   * @throws IOException
   */
  private ApplicationMaster() {
    super(ApplicationMaster.class.getName());

    conf = new XLearningConfiguration();
    conf.addResource(new Path(XLearningConstants.XLEARNING_JOB_CONFIGURATION));
    System.setProperty(XLearningConstants.Environment.HADOOP_USER_NAME.toString(), conf.get("hadoop.job.ugi").split(",")[0]);
    outputInfos = new ArrayList<>();
    input2FileStatus = new ConcurrentHashMap<>();
    containerId2InputInfo = new ConcurrentHashMap<>();
    inputFileSplits = null;
    containerId2InputSplit = new ConcurrentHashMap<>();
    statusUpdateInterval = conf.getInt(XLearningConfiguration.XLEARNING_STATUS_UPDATE_INTERVAL, XLearningConfiguration.DEFAULT_XLEARNING_STATUS_PULL_INTERVAL);
    applicationAttemptID = Records.newRecord(ApplicationAttemptId.class);
    applicationMessageQueue = new LinkedBlockingQueue<>(
        conf.getInt(XLearningConfiguration.XLEARNING_MESSAGES_LEN_MAX, XLearningConfiguration.DEFAULT_XLEARNING_MESSAGES_LEN_MAX));
    containerLocalResource = new HashMap<>();
    applicationContext = new RunningAppContext();

    envs = System.getenv();
    workerMemory = conf.getInt(XLearningConfiguration.XLEARNING_WORKER_MEMORY, XLearningConfiguration.DEFAULT_XLEARNING_WORKER_MEMORY);
    workerVCores = conf.getInt(XLearningConfiguration.XLEARNING_WORKER_VCORES, XLearningConfiguration.DEFAULT_XLEARNING_WORKER_VCORES);
    workerNum = conf.getInt(XLearningConfiguration.XLEARNING_WORKER_NUM, XLearningConfiguration.DEFAULT_XLEARNING_WORKER_NUM);
    psMemory = conf.getInt(XLearningConfiguration.XLEARNING_PS_MEMORY, XLearningConfiguration.DEFAULT_XLEARNING_PS_MEMORY);
    psVCores = conf.getInt(XLearningConfiguration.XLEARNING_PS_VCORES, XLearningConfiguration.DEFAULT_XLEARNING_PS_VCORES);
    psNum = conf.getInt(XLearningConfiguration.XLEARNING_PS_NUM, XLearningConfiguration.DEFAULT_XLEARNING_PS_NUM);
    single = conf.getBoolean(XLearningConfiguration.XLEARNING_TF_MODE_SINGLE, XLearningConfiguration.DEFAULT_XLEARNING_TF_MODE_SINGLE);
    singleMx = conf.getBoolean(XLearningConfiguration.XLEARNING_MXNET_MODE_SINGLE, XLearningConfiguration.DEFAULT_XLEARNING_MXNET_MODE_SINGLE);
    appPriority = conf.getInt(XLearningConfiguration.XLEARNING_APP_PRIORITY, XLearningConfiguration.DEFAULT_XLEARNING_APP_PRIORITY);
    acquiredWorkerContainers = new ArrayList<>();
    acquiredPsContainers = new ArrayList<>();
    dmlcPsRootUri = null;
    dmlcPsRootPort = 0;
    dmlcTrackerUri = null;
    dmlcTrackerPort = 0;

    if (envs.containsKey(ApplicationConstants.Environment.CONTAINER_ID.toString())) {
      ContainerId containerId = ConverterUtils
          .toContainerId(envs.get(ApplicationConstants.Environment.CONTAINER_ID.toString()));
      applicationAttemptID = containerId.getApplicationAttemptId();
    } else {
      throw new IllegalArgumentException(
          "Application Attempt Id is not available in environment");
    }

    LOG.info("Application appId="
        + applicationAttemptID.getApplicationId().getId()
        + ", clustertimestamp="
        + applicationAttemptID.getApplicationId().getClusterTimestamp()
        + ", attemptId=" + applicationAttemptID.getAttemptId());

    if (applicationAttemptID.getAttemptId() > 1 && (conf.getInt(XLearningConfiguration.XLEARNING_APP_MAX_ATTEMPTS, XLearningConfiguration.DEFAULT_XLEARNING_APP_MAX_ATTEMPTS) > 1)) {
      int maxMem = Integer.valueOf(envs.get(XLearningConstants.Environment.XLEARNING_CONTAINER_MAX_MEMORY.toString()));
      LOG.info("maxMem : " + maxMem);
      workerMemory = workerMemory + (applicationAttemptID.getAttemptId() - 1) * (int) Math.ceil(workerMemory * conf.getDouble(XLearningConfiguration.XLEARNING_WORKER_MEM_AUTO_SCALE, XLearningConfiguration.DEFAULT_XLEARNING_WORKER_MEM_AUTO_SCALE));
      LOG.info("Auto Scale the Worker Memory from " + conf.getInt(XLearningConfiguration.XLEARNING_WORKER_MEMORY, XLearningConfiguration.DEFAULT_XLEARNING_WORKER_MEMORY) + " to " + workerMemory);
      if (workerMemory > maxMem) {
        workerMemory = maxMem;
      }
      if (psNum > 0) {
        psMemory = psMemory + (applicationAttemptID.getAttemptId() - 1) * (int) Math.ceil(psMemory * conf.getDouble(XLearningConfiguration.XLEARNING_PS_MEM_AUTO_SCALE, XLearningConfiguration.DEFAULT_XLEARNING_PS_MEM_AUTO_SCALE));
        LOG.info("Auto Scale the Ps Memory from " + conf.getInt(XLearningConfiguration.XLEARNING_PS_MEMORY, XLearningConfiguration.DEFAULT_XLEARNING_PS_MEMORY) + " to " + psMemory);
        if (psMemory > maxMem) {
          psMemory = maxMem;
        }
      }
    }

    if (envs.containsKey(XLearningConstants.Environment.XLEARNING_FILES_LOCATION.toString())) {
      appFilesRemoteLocation = envs.get(XLearningConstants.Environment.XLEARNING_FILES_LOCATION.toString());
      LOG.info("Application files location: " + appFilesRemoteLocation);
    }

    if (envs.containsKey(XLearningConstants.Environment.XLEARNING_LIBJARS_LOCATION.toString())) {
      appLibJarsRemoteLocation = envs.get(XLearningConstants.Environment.XLEARNING_LIBJARS_LOCATION.toString());
      LOG.info("Application lib Jars location: " + appLibJarsRemoteLocation);
    }

    if (envs.containsKey(XLearningConstants.Environment.XLEARNING_CACHE_FILE_LOCATION.toString())) {
      appCacheFilesRemoteLocation = envs.get(XLearningConstants.Environment.XLEARNING_CACHE_FILE_LOCATION.toString());
      LOG.info("Application cacheFiles location: " + appCacheFilesRemoteLocation);
    }

    if (envs.containsKey(XLearningConstants.Environment.XLEARNING_CACHE_ARCHIVE_LOCATION.toString())) {
      appCacheArchivesRemoteLocation = envs.get(XLearningConstants.Environment.XLEARNING_CACHE_ARCHIVE_LOCATION.toString());
      LOG.info("Application cacheArchive location: " + appCacheArchivesRemoteLocation);
    }

    assert (envs.containsKey(XLearningConstants.Environment.APP_JAR_LOCATION.toString()));
    appJarRemoteLocation = new Path(envs.get(XLearningConstants.Environment.APP_JAR_LOCATION.toString()));
    LOG.info("Application jar location: " + appJarRemoteLocation);

    assert (envs.containsKey(XLearningConstants.Environment.XLEARNING_JOB_CONF_LOCATION.toString()));
    appConfRemoteLocation = new Path(envs.get(XLearningConstants.Environment.XLEARNING_JOB_CONF_LOCATION.toString()));
    LOG.info("Application conf location: " + appConfRemoteLocation);

    if (envs.containsKey(XLearningConstants.Environment.XLEARNING_EXEC_CMD.toString())) {
      xlearningCommand = envs.get(XLearningConstants.Environment.XLEARNING_EXEC_CMD.toString());
      LOG.info("XLearning exec command: " + xlearningCommand);
    }

    if (envs.containsKey(XLearningConstants.Environment.XLEARNING_APP_TYPE.toString())) {
      xlearningAppType = envs.get(XLearningConstants.Environment.XLEARNING_APP_TYPE.toString()).toUpperCase();
      LOG.info("XLearning app type: " + xlearningAppType);
    } else {
      xlearningAppType = XLearningConfiguration.DEFAULT_XLEARNING_APP_TYPE.toUpperCase();
      LOG.info("XLearning app type: " + xlearningAppType);
    }

    if (envs.containsKey(ApplicationConstants.Environment.NM_HOST.toString())) {
      applicationMasterHostname = envs.get(ApplicationConstants.Environment.NM_HOST.toString());
    }

    this.messageService = new ApplicationMessageService(this.applicationContext, conf);
    this.webService = new ApplicationWebService(this.applicationContext, conf);
    this.containerListener = new ApplicationContainerListener(applicationContext, conf);

    this.startSavingModel = false;
    this.lastSavingStatus = false;
    this.savingModelList = new ArrayList<>();
  }

  private void init() {
    appendMessage(new Message(LogType.STDERR, "ApplicationMaster starting services"));

    this.rmCallbackHandler = new RMCallbackHandler();
    this.amrmAsync = AMRMClientAsync.createAMRMClientAsync(1000, rmCallbackHandler);
    this.amrmAsync.init(conf);

    NMCallbackHandler nmAsyncHandler = new NMCallbackHandler();
    this.nmAsync = NMClientAsync.createNMClientAsync(nmAsyncHandler);
    this.nmAsync.init(conf);

    addService(this.amrmAsync);
    addService(this.nmAsync);
    addService(this.messageService);
    addService(this.webService);
    addService(this.containerListener);
    try {
      super.serviceStart();
    } catch (Exception e) {
      throw new RuntimeException("Error start application services!", e);
    }

    applicationMasterTrackingUrl = applicationMasterHostname + ":" + this.webService.getHttpPort();
    applicationHistoryUrl = conf.get(XLearningConfiguration.XLEARNING_HISTORY_WEBAPP_ADDRESS,
        XLearningConfiguration.DEFAULT_XLEARNING_HISTORY_WEBAPP_ADDRESS) + "/jobhistory/job/"
        + applicationAttemptID.getApplicationId();
    LOG.info("master tracking url:" + applicationMasterTrackingUrl);
    LOG.info("history url: " + applicationHistoryUrl);

    cleanApplication = new Thread(new Runnable() {
      @Override
      public void run() {
        System.clearProperty(XLearningConstants.Environment.HADOOP_USER_NAME.toString());
        YarnConfiguration xlearningConf = new YarnConfiguration();
        if (xlearningConf.getBoolean(XLearningConfiguration.XLEARNING_CLEANUP_ENABLE, XLearningConfiguration.DEFAULT_XLEARNING_CLEANUP_ENABLE)) {
          Path stagingDir = new Path(envs.get(XLearningConstants.Environment.XLEARNING_STAGING_LOCATION.toString()));
          try {
            stagingDir.getFileSystem(xlearningConf).delete(stagingDir);
            LOG.info("Deleting the staging file successed.");
          } catch (Exception e) {
            LOG.error("Deleting the staging file Error." + e);
          }
        }

        try {
          FsPermission LOG_FILE_PERMISSION = FsPermission.createImmutable((short) 0777);
          Path jobLogPath = new Path(xlearningConf.get("fs.defaultFS") + conf.get(XLearningConfiguration.XLEARNING_HISTORY_LOG_DIR,
              XLearningConfiguration.DEFAULT_XLEARNING_HISTORY_LOG_DIR) + "/" + applicationAttemptID.getApplicationId().toString()
              + "/" + applicationAttemptID.getApplicationId().toString());
          LOG.info("jobLogPath:" + jobLogPath.toString());
          LOG.info("Start write the log to " + jobLogPath.toString());
          FileSystem fs = FileSystem.get(xlearningConf);
          FSDataOutputStream out = fs.create(jobLogPath);
          fs.setPermission(jobLogPath, new FsPermission(LOG_FILE_PERMISSION));
          Map<String, Object> logMessage = new HashMap<>();
          logMessage.put(AMParams.APP_TYPE, xlearningAppType);

          String tensorboardInfo = "-";
          if (conf.getBoolean(XLearningConfiguration.XLEARNING_TF_BOARD_ENABLE, XLearningConfiguration.DEFAULT_XLEARNING_TF_BOARD_ENABLE)) {
            String boardLogPath;
            if (conf.get(XLearningConfiguration.XLEARNING_TF_BOARD_LOG_DIR, XLearningConfiguration.DEFAULT_XLEARNING_TF_BOARD_LOG_DIR).indexOf("hdfs://") == -1) {
              if (conf.get(XLearningConfiguration.XLEARNING_TF_BOARD_HISTORY_DIR, XLearningConfiguration.DEFAULT_XLEARNING_TF_BOARD_HISTORY_DIR).equals(xlearningConf.get(XLearningConfiguration.XLEARNING_TF_BOARD_HISTORY_DIR, XLearningConfiguration.DEFAULT_XLEARNING_TF_BOARD_HISTORY_DIR))) {
                boardLogPath = xlearningConf.get("fs.defaultFS") + conf.get(XLearningConfiguration.XLEARNING_TF_BOARD_HISTORY_DIR,
                    XLearningConfiguration.DEFAULT_XLEARNING_TF_BOARD_HISTORY_DIR) + "/" + applicationAttemptID.getApplicationId().toString();
              } else {
                boardLogPath = conf.get("fs.defaultFS") + conf.get(XLearningConfiguration.XLEARNING_TF_BOARD_HISTORY_DIR,
                    XLearningConfiguration.DEFAULT_XLEARNING_TF_BOARD_HISTORY_DIR);
              }
            } else {
              boardLogPath = conf.get(XLearningConfiguration.XLEARNING_TF_BOARD_LOG_DIR);
            }
            tensorboardInfo = boardLogPath;
          }
          logMessage.put(AMParams.BOARD_INFO, tensorboardInfo);

          String userName = StringUtils.split(conf.get("hadoop.job.ugi"), ',')[0];
          List<Container> workerContainers = applicationContext.getWorkerContainers();
          List<Container> psContainers = applicationContext.getPsContainers();
          Map<XLearningContainerId, String> reporterProgress = applicationContext.getReporterProgress();
          Map<XLearningContainerId, String> containersAppStartTime = applicationContext.getContainersAppStartTime();
          Map<XLearningContainerId, String> containersAppFinishTime = applicationContext.getContainersAppFinishTime();
          for (Container container : workerContainers) {
            Map<String, String> containerMessage = new HashMap<>();
            containerMessage.put(AMParams.CONTAINER_HTTP_ADDRESS, container.getNodeHttpAddress());
            containerMessage.put(AMParams.CONTAINER_ROLE, "worker");
            if (applicationContext.getContainerStatus(new XLearningContainerId(container.getId())) != null) {
              containerMessage.put(AMParams.CONTAINER_STATUS, applicationContext.getContainerStatus(new XLearningContainerId(container.getId())).toString());
            } else {
              containerMessage.put(AMParams.CONTAINER_STATUS, "-");
            }
            if (containersAppStartTime.get(new XLearningContainerId(container.getId())) != null && !containersAppStartTime.get(new XLearningContainerId(container.getId())).equals("")) {
              String localStartTime = containersAppStartTime.get(new XLearningContainerId(container.getId()));
              containerMessage.put(AMParams.CONTAINER_START_TIME, localStartTime);
            } else {
              containerMessage.put(AMParams.CONTAINER_START_TIME, "N/A");
            }
            if (containersAppFinishTime.get(new XLearningContainerId(container.getId())) != null && !containersAppFinishTime.get(new XLearningContainerId(container.getId())).equals("")) {
              String localFinishTime = containersAppFinishTime.get(new XLearningContainerId(container.getId()));
              containerMessage.put(AMParams.CONTAINER_FINISH_TIME, localFinishTime);
            } else {
              containerMessage.put(AMParams.CONTAINER_FINISH_TIME, "N/A");
            }

            ConcurrentHashMap<String, LinkedBlockingDeque<Object>> cpuMetrics = applicationContext.getContainersCpuMetrics().get(new XLearningContainerId(container.getId()));
            containerMessage.put(AMParams.CONTAINER_CPU_METRICS, new Gson().toJson(cpuMetrics));

            if (reporterProgress.get(new XLearningContainerId(container.getId())) != null && !reporterProgress.get(new XLearningContainerId(container.getId())).equals("")) {
              String progressLog = reporterProgress.get(new XLearningContainerId(container.getId()));
              String[] progress = progressLog.toString().split(":");
              if (progress.length != 2) {
                containerMessage.put(AMParams.CONTAINER_REPORTER_PROGRESS, "progress log format error");
              } else {
                try {
                  Float percentProgress = Float.parseFloat(progress[1]);
                  if (percentProgress < 0.0 || percentProgress > 1.0) {
                    containerMessage.put(AMParams.CONTAINER_REPORTER_PROGRESS, "progress log format error");
                  } else {
                    DecimalFormat df = new DecimalFormat("0.00");
                    df.setRoundingMode(RoundingMode.HALF_UP);
                    containerMessage.put(AMParams.CONTAINER_REPORTER_PROGRESS, df.format((Float.parseFloat(progress[1]) * 100)) + "%");
                  }
                } catch (Exception e) {
                  containerMessage.put(AMParams.CONTAINER_REPORTER_PROGRESS, "progress log format error");
                }
              }
            } else {
              containerMessage.put(AMParams.CONTAINER_REPORTER_PROGRESS, "0.00%");
            }
            containerMessage.put(AMParams.CONTAINER_LOG_ADDRESS, String.format("http://%s/node/containerlogs/%s/%s",
                container.getNodeHttpAddress(),
                container.getId().toString(),
                userName));
            logMessage.put(container.getId().toString(), containerMessage);
          }

          for (Container container : psContainers) {
            Map<String, String> containerMessage = new HashMap<>();
            containerMessage.put(AMParams.CONTAINER_HTTP_ADDRESS, container.getNodeHttpAddress());
            if (xlearningAppType.equals("TENSORFLOW")) {
              containerMessage.put(AMParams.CONTAINER_ROLE, "ps");
            } else if (xlearningAppType.equals("MXNET")) {
              containerMessage.put(AMParams.CONTAINER_ROLE, "server");
            }

            if (applicationContext.getContainerStatus(new XLearningContainerId(container.getId())) != null) {
              containerMessage.put(AMParams.CONTAINER_STATUS, applicationContext.getContainerStatus(new XLearningContainerId(container.getId())).toString());
            } else {
              containerMessage.put(AMParams.CONTAINER_STATUS, "-");
            }

            if (containersAppStartTime.get(new XLearningContainerId(container.getId())) != null && !containersAppStartTime.get(new XLearningContainerId(container.getId())).equals("")) {
              String localStartTime = containersAppStartTime.get(new XLearningContainerId(container.getId()));
              containerMessage.put(AMParams.CONTAINER_START_TIME, localStartTime);
            } else {
              containerMessage.put(AMParams.CONTAINER_START_TIME, "N/A");
            }
            if (containersAppFinishTime.get(new XLearningContainerId(container.getId())) != null && !containersAppFinishTime.get(new XLearningContainerId(container.getId())).equals("")) {
              String localFinishTime = containersAppFinishTime.get(new XLearningContainerId(container.getId()));
              containerMessage.put(AMParams.CONTAINER_FINISH_TIME, localFinishTime);
            } else {
              containerMessage.put(AMParams.CONTAINER_FINISH_TIME, "N/A");
            }
            containerMessage.put(AMParams.CONTAINER_REPORTER_PROGRESS, "0.00%");
            containerMessage.put(AMParams.CONTAINER_LOG_ADDRESS, String.format("http://%s/node/containerlogs/%s/%s",
                container.getNodeHttpAddress(),
                container.getId().toString(),
                userName));
            logMessage.put(container.getId().toString(), containerMessage);
          }

          List<String> savedTimeStamp = new ArrayList<>();
          List<String> outputList = new ArrayList<>();
          if (applicationContext.getOutputs().size() == 0) {
            outputList.add("-");
            savedTimeStamp.add("-");
          } else {
            for (OutputInfo output : applicationContext.getOutputs()) {
              outputList.add(output.getDfsLocation());
            }
            if (applicationContext.getModelSavingList().size() == 0) {
              savedTimeStamp.add("-");
            } else {
              for (int i = applicationContext.getModelSavingList().size(); i > 0; i--) {
                savedTimeStamp.add(String.valueOf(applicationContext.getModelSavingList().get(i - 1)));
              }
            }
          }
          logMessage.put(AMParams.TIMESTAMP_LIST, savedTimeStamp);
          logMessage.put(AMParams.OUTPUT_PATH, outputList);
          logMessage.put(AMParams.WORKER_NUMBER, String.valueOf(workerNum));

          out.writeBytes(new Gson().toJson(logMessage));
          out.close();
          fs.close();
          LOG.info("Writing the history log file successed.");
        } catch (Exception e) {
          LOG.error("Writing the history log file Error." + e);
        }
      }
    });
    Runtime.getRuntime().addShutdownHook(cleanApplication);
  }


  private void buildS3InputFileStatus(){  //todo
    String xlearningInputs = envs.get(XLearningConstants.Environment.XLEARNING_INPUTS.toString());
    if (StringUtils.isBlank(xlearningInputs)) {
      LOG.info("Application has no inputs");
      return;
    }

    String[] inputs = StringUtils.split(xlearningInputs, "|");
    //node  , bucketName and aliasDir must be the same
    if(inputs.length != 1){
      LOG.error("xlearningInputs length is not 1");
      return;
    }
    //model-data1/test/data#data

    LOG.info("#### input path : " + inputs[0]);
    if (inputs != null && inputs.length > 0) {

      String[] inputPathTuple = StringUtils.split(inputs[0], "#");
      if (inputPathTuple.length < 2) {
        throw new RuntimeException("Error input path format " + xlearningInputs);
      }
      String  bucketName2prefix = inputPathTuple[0];
      String[]  tmp = bucketName2prefix.split("/");
      String bucketName = tmp[0];
      String prefix = "";
      for(int i=1;i< tmp.length;++i){
        prefix += tmp[i];
        if(i != tmp.length -1 ){
          prefix += "/";
        }
      }
      String  aliasDir = inputPathTuple[1];

      LOG.info("bucketName :" + bucketName + ", prefix:" + prefix);

      final AmazonS3 s3 = AmazonS3ClientBuilder.defaultClient();

      ListObjectsV2Result result = s3.listObjectsV2(bucketName,prefix);
      List<S3ObjectSummary> objects = result.getObjectSummaries();
      s3ObjectEntrys = new ArrayList<S3ObjectEntry>();
      for (S3ObjectSummary os: objects) {
        System.out.println("--> " + os.getKey());
        S3ObjectEntry entry = new S3ObjectEntry(bucketName,os.getKey(),aliasDir);
        s3ObjectEntrys.add(entry);
      }

      //s3InputInfo = new S3InputInfo(bucketName,aliasDir,keys);
      for(S3ObjectEntry entry:s3ObjectEntrys  ){
        LOG.info(" => " + entry.toString());
      }

    }

  }
  private void buildInputFileStatus() {
    String xlearningInputs = envs.get(XLearningConstants.Environment.XLEARNING_INPUTS.toString());
    if (StringUtils.isBlank(xlearningInputs)) {
      LOG.info("Application has no inputs");
      return;
    }

    String[] inputs = StringUtils.split(xlearningInputs, "|");
    if (inputs != null && inputs.length > 0) {
      for (String input : inputs) {
        String[] inputPathTuple = StringUtils.split(input, "#");
        if (inputPathTuple.length < 2) {
          throw new RuntimeException("Error input path format " + xlearningInputs);
        }
        List<FileStatus> fileStatus = new ArrayList<>();
        String inputPathRemote = inputPathTuple[0];
        if (!StringUtils.isBlank(inputPathRemote)) {
          for (String singlePath : StringUtils.split(inputPathRemote, ",")) {

            try {
              FileStatus[] status = FileSystem.get(conf).globStatus(new Path(singlePath));
              for(int i =0 ;i< status.length ;++i){
                LOG.info(status[i].toString());
              }
              fileStatus.addAll(Arrays.asList(status));
            } catch (IOException e) {
              e.printStackTrace();
            }
            /*
            Path inputPath = new Path(singlePath);
            try {
              inputPath = inputPath.getFileSystem(conf).makeQualified(inputPath);

              List<FileStatus> downLoadFile = Utilities.listStatusRecursively(inputPath,
                  inputPath.getFileSystem(conf), null);
              fileStatus.addAll(downLoadFile);
            } catch (IOException e) {
              e.printStackTrace();
            }
            */
          }
          input2FileStatus.put(inputPathTuple[1], fileStatus);
          if (fileStatus.size() > 0) {
            if (fileStatus.size() < workerNum) {
              LOG.warn("File count in  " + inputPathRemote + "  " + fileStatus.size() +
                  " less than the worker count " + workerNum);
            }
          }
        } else {
          throw new RuntimeException("Error input path format " + xlearningInputs);
        }
      }
    }
  }

  public void buildInputStreamFileStatus() throws IOException {
    String xlearningInputs = envs.get(XLearningConstants.Environment.XLEARNING_INPUTS.toString());
    if (StringUtils.isBlank(xlearningInputs)) {
      LOG.info("Application has no inputs");
      return;
    }

    String[] inputPathTuple = StringUtils.split(xlearningInputs, "#");
    if (inputPathTuple.length < 2) {
      throw new RuntimeException("Error input path format " + xlearningInputs);
    }
    String inputPathRemote = inputPathTuple[0];
    if (!StringUtils.isBlank(inputPathRemote)) {
      JobConf jobConf = new JobConf(conf);
      jobConf.set(XLearningConstants.STREAM_INPUT_DIR, inputPathRemote);
      InputFormat inputFormat = ReflectionUtils.newInstance(conf.getClass(XLearningConfiguration.XLEARNING_INPUTF0RMAT_CLASS, XLearningConfiguration.DEFAULT_XLEARNING_INPUTF0RMAT_CLASS, InputFormat.class),
          jobConf);
      inputFileSplits = inputFormat.getSplits(jobConf, 1);
    } else {
      throw new RuntimeException("Error input path format " + xlearningInputs);
    }
  }


  private void allocateS3InputSplits(){

    containerId2S3InputInfo = new ConcurrentHashMap<XLearningContainerId, S3InputInfo>();
    int entryIndex = 0 ;
    for (Container container : acquiredWorkerContainers) {
      LOG.info("Initializing " + container.getId().toString() + " input splits");
      containerId2S3InputInfo.putIfAbsent(new XLearningContainerId(container.getId()), new S3InputInfo());
    }
    for(S3ObjectEntry entry : s3ObjectEntrys){
      int containerIndex =  entryIndex % acquiredWorkerContainers.size();
      XLearningContainerId containerId = new XLearningContainerId(acquiredWorkerContainers.get(containerIndex).getId());
      LOG.info("containerId : " + containerId + " add path :" + entry.getPath() ) ;
      containerId2S3InputInfo.get(containerId).getPaths().add(entry.getPath());
      containerId2S3InputInfo.get(containerId).setAliasName(entry.getAliaseDir());
      containerId2S3InputInfo.get(containerId).setBucket(entry.getBucket());
      entryIndex ++;
    }


  }
  @SuppressWarnings("deprecation")
  private void allocateInputSplits() {

    for (Container container : acquiredWorkerContainers) {
      LOG.info("Initializing " + container.getId().toString() + " input splits");
      containerId2InputInfo.putIfAbsent(new XLearningContainerId(container.getId()), new ArrayList<InputInfo>());
    }
    Set<String> fileKeys = input2FileStatus.keySet();
    for (String fileName : fileKeys) {
      List<FileStatus> files = input2FileStatus.get(fileName);
      List<Path> paths = Utilities.convertStatusToPath(files);
      ConcurrentHashMap<XLearningContainerId, ConcurrentHashMap<String, InputInfo>> containersFiles = new ConcurrentHashMap<>();
      for (int i = 0, len = paths.size(); i < len; i++) {
        Integer index = i % workerNum;
        ConcurrentHashMap<String, InputInfo> mapSplit;
        XLearningContainerId containerId = new XLearningContainerId(acquiredWorkerContainers.get(index).getId());
        if (containersFiles.containsKey(containerId)) {
          mapSplit = containersFiles.get(containerId);
        } else {
          mapSplit = new ConcurrentHashMap<>();
          containersFiles.put(containerId, mapSplit);
        }
        if (mapSplit.containsKey(fileName)) {
          mapSplit.get(fileName).addPath(paths.get(i));
        } else {
          InputInfo inputInfo = new InputInfo();
          inputInfo.setAliasName(fileName);
          List<Path> ps = new ArrayList<>();
          ps.add(paths.get(i));
          inputInfo.setPaths(ps);
          mapSplit.put(fileName, inputInfo);
        }
      }
      Set<XLearningContainerId> containerIdSet = containersFiles.keySet();
      for (XLearningContainerId containerId : containerIdSet) {
        containerId2InputInfo.get(containerId).add(containersFiles.get(containerId).get(fileName));
        LOG.info("put " + fileName + " to " + containerId.toString());
      }
    }
    LOG.info("inputInfo " + new Gson().toJson(containerId2InputInfo));
  }

  private void allocateInputStreamSplits() {

    for (Container container : acquiredWorkerContainers) {
      LOG.info("Initializing " + container.getId().toString() + " input splits");
      containerId2InputSplit.putIfAbsent(new XLearningContainerId(container.getId()), new ArrayList<InputSplit>());
    }
    if (conf.getBoolean(XLearningConfiguration.XLEARNING_INPUT_STREAM_SHUFFLE, XLearningConfiguration.DEFAULT_XLEARNING_INPUT_STREAM_SHUFFLE)) {
      LOG.info("XLEARNING_INPUT_STREAM_SHUFFLE is true");
      for (int i = 0, len = inputFileSplits.length; i < len; i++) {
        Integer index = i % workerNum;
        XLearningContainerId containerId = new XLearningContainerId(acquiredWorkerContainers.get(index).getId());
        containerId2InputSplit.get(containerId).add(inputFileSplits[i]);
        LOG.info("put split " + (i + 1) + " to " + containerId.toString());
      }
    } else {
      LOG.info("XLEARNING_INPUT_STREAM_SHUFFLE is false");
      int nsplit = inputFileSplits.length / workerNum;
      int msplit = inputFileSplits.length % workerNum;
      int count = 0;
      for (int i = 0; i < workerNum; i++) {
        XLearningContainerId containerId = new XLearningContainerId(acquiredWorkerContainers.get(i).getId());
        for (int j = 0; j < nsplit; j++) {
          containerId2InputSplit.get(containerId).add(inputFileSplits[count++]);
          LOG.info("put split " + count + " to " + containerId.toString());
        }
        if (msplit > 0) {
          containerId2InputSplit.get(containerId).add(inputFileSplits[count++]);
          LOG.info("put split " + count + " to " + containerId.toString());
          msplit--;
        }
      }
    }
  }

  private void buildOutputLocations() {
    String xlearningOutputs = envs.get(XLearningConstants.Environment.XLEARNING_OUTPUTS.toString());
    if (StringUtils.isBlank(xlearningOutputs)) {
      return;
    }
    String[] outputs = StringUtils.split(xlearningOutputs, "|");
    if (outputs != null && outputs.length > 0) {
      for (String output : outputs) {
        String outputPathTuple[] = StringUtils.split(output, "#");
        if (outputPathTuple.length < 2) {
          throw new RuntimeException("Error input path format " + xlearningOutputs);
        }
        String pathRemote = outputPathTuple[0];
        OutputInfo outputInfo = new OutputInfo();
        outputInfo.setDfsLocation(pathRemote);
        String pathLocal = outputPathTuple[1];
        outputInfo.setLocalLocation(pathLocal);
        outputInfos.add(outputInfo);
        LOG.info("Application output " + pathRemote + "#" + pathLocal);
      }
    } else {
      throw new RuntimeException("Error input path format " + xlearningOutputs);
    }
  }

  private void registerApplicationMaster() {
    try {
      amrmAsync.registerApplicationMaster(this.messageService.getServerAddress().getHostName(),
          this.messageService.getServerAddress().getPort(), applicationMasterTrackingUrl);
    } catch (Exception e) {
      throw new RuntimeException("Registering application master failed,", e);
    }
  }

  private void buildContainerRequest() {
    Priority priority = Records.newRecord(Priority.class);
    priority.setPriority(appPriority);
    Resource workerCapability = Records.newRecord(Resource.class);
    workerCapability.setMemory(workerMemory);
    workerCapability.setVirtualCores(workerVCores);
    workerContainerRequest = new ContainerRequest(workerCapability, null, null, priority);
    LOG.info("Create worker container request: " + workerContainerRequest.toString());

    if (("TENSORFLOW".equals(xlearningAppType) && !single) || ("MXNET".equals(xlearningAppType) && !singleMx)) {
      Resource psCapability = Records.newRecord(Resource.class);
      psCapability.setMemory(psMemory);
      psCapability.setVirtualCores(psVCores);
      psContainerRequest = new ContainerRequest(psCapability, null, null, priority);
      LOG.info("Create ps container request: " + psContainerRequest.toString());
    }
  }

  private void buildContainerLocalResource() {
    URI defaultUri = new Path(conf.get("fs.defaultFS")).toUri();
    LOG.info("default URI is " + defaultUri.toString());
    containerLocalResource = new HashMap<>();
    try {
      containerLocalResource.put(XLearningConstants.XLEARNING_APPLICATION_JAR,
          Utilities.createApplicationResource(appJarRemoteLocation.getFileSystem(conf),
              appJarRemoteLocation,
              LocalResourceType.FILE));
      containerLocalResource.put(XLearningConstants.XLEARNING_JOB_CONFIGURATION,
          Utilities.createApplicationResource(appConfRemoteLocation.getFileSystem(conf),
              appConfRemoteLocation,
              LocalResourceType.FILE));

      if (appCacheFilesRemoteLocation != null) {
        String[] cacheFiles = StringUtils.split(appCacheFilesRemoteLocation, ",");
        for (String path : cacheFiles) {
          Path pathRemote;
          String aliasName;
          if (path.contains("#")) {
            String[] paths = StringUtils.split(path, "#");
            if (paths.length != 2) {
              throw new RuntimeException("Error cacheFile path format " + appCacheFilesRemoteLocation);
            }
            pathRemote = new Path(paths[0]);
            aliasName = paths[1];
          } else {
            pathRemote = new Path(path);
            aliasName = pathRemote.getName();
          }
          URI pathRemoteUri = pathRemote.toUri();
          if (pathRemoteUri.getScheme() == null || pathRemoteUri.getHost() == null) {
            pathRemote = new Path(defaultUri.toString(), pathRemote.toString());
          }
          LOG.info("Cache file remote path is " + pathRemote + " and alias name is " + aliasName);
          containerLocalResource.put(aliasName,
              Utilities.createApplicationResource(pathRemote.getFileSystem(conf),
                  pathRemote,
                  LocalResourceType.FILE));
        }
      }

      if (appCacheArchivesRemoteLocation != null) {
        String[] cacheArchives = StringUtils.split(appCacheArchivesRemoteLocation, ",");
        for (String path : cacheArchives) {
          Path pathRemote;
          String aliasName;
          if (path.contains("#")) {
            String[] paths = StringUtils.split(path, "#");
            if (paths.length != 2) {
              throw new RuntimeException("Error cacheArchive path format " + appCacheArchivesRemoteLocation);
            }
            pathRemote = new Path(paths[0]);
            aliasName = paths[1];
          } else {
            pathRemote = new Path(path);
            aliasName = pathRemote.getName();
          }
          URI pathRemoteUri = pathRemote.toUri();
          if (pathRemoteUri.getScheme() == null || pathRemoteUri.getHost() == null) {
            pathRemote = new Path(defaultUri.toString(), pathRemote.toString());
          }
          LOG.info("Cache archive remote path is " + pathRemote + " and alias name is " + aliasName);
          containerLocalResource.put(aliasName,
              Utilities.createApplicationResource(pathRemote.getFileSystem(conf),
                  pathRemote,
                  LocalResourceType.ARCHIVE));
        }
      }

      if (appFilesRemoteLocation != null) {
        String[] xlearningFiles = StringUtils.split(appFilesRemoteLocation, ",");
        for (String file : xlearningFiles) {
          Path path = new Path(file);
          containerLocalResource.put(path.getName(),
              Utilities.createApplicationResource(path.getFileSystem(conf),
                  path,
                  LocalResourceType.FILE));
        }
      }

      if (appLibJarsRemoteLocation != null) {
        String[] jarFiles = StringUtils.split(appLibJarsRemoteLocation, ",");
        for (String file : jarFiles) {
          Path path = new Path(file);
          containerLocalResource.put(path.getName(),
              Utilities.createApplicationResource(path.getFileSystem(conf),
                  path,
                  LocalResourceType.FILE));
        }
      }

    } catch (IOException e) {
      throw new RuntimeException("Error while build container local resource", e);
    }
  }

  private Map<String, String> buildContainerEnv(String role) {
    LOG.info("Setting environments for the Container");
    Map<String, String> containerEnv = new HashMap<>();
    containerEnv.put(XLearningConstants.Environment.HADOOP_USER_NAME.toString(), conf.get("hadoop.job.ugi").split(",")[0]);
    containerEnv.put(XLearningConstants.Environment.XLEARNING_TF_ROLE.toString(), role);
    containerEnv.put(XLearningConstants.Environment.XLEARNING_EXEC_CMD.toString(), xlearningCommand);
    containerEnv.put(XLearningConstants.Environment.XLEARNING_APP_TYPE.toString(), xlearningAppType);
    if(envs.get(XLearningConstants.Environment.USE_S3.toString()).equalsIgnoreCase("yes")){
      containerEnv.put(XLearningConstants.Environment.USE_S3.toString(), "yes");
    }
    if (xlearningAppType.equals("MXNET") && !singleMx) {
      containerEnv.put(XLearningConstants.Environment.XLEARNING_MXNET_WORKER_NUM.toString(), String.valueOf(workerNum));
      containerEnv.put(XLearningConstants.Environment.XLEARNING_MXNET_SERVER_NUM.toString(), String.valueOf(psNum));
      containerEnv.put("DMLC_PS_ROOT_URI", dmlcPsRootUri);
      containerEnv.put("DMLC_PS_ROOT_PORT", String.valueOf(dmlcPsRootPort));
    }

    if (xlearningAppType.equals("DISTXGBOOST")) {
      containerEnv.put("DMLC_NUM_WORKER", String.valueOf(workerNum));
      containerEnv.put("DMLC_TRACKER_URI", dmlcTrackerUri);
      containerEnv.put("DMLC_TRACKER_PORT", String.valueOf(dmlcTrackerPort));
    }

    if (xlearningAppType.equals("DISTLIGHTGBM")) {
      containerEnv.put(XLearningConstants.Environment.XLEARNING_LIGHTGBM_WORKER_NUM.toString(), String.valueOf(workerNum));
    }

    containerEnv.put("CLASSPATH", System.getenv("CLASSPATH"));
    containerEnv.put(XLearningConstants.Environment.APP_ATTEMPTID.toString(), applicationAttemptID.toString());
    containerEnv.put(XLearningConstants.Environment.APP_ID.toString(), applicationAttemptID.getApplicationId().toString());

    containerEnv.put(XLearningConstants.Environment.APPMASTER_HOST.toString(),
        System.getenv(ApplicationConstants.Environment.NM_HOST.toString()));
    containerEnv.put(XLearningConstants.Environment.APPMASTER_PORT.toString(),
        String.valueOf(containerListener.getServerPort()));
    containerEnv.put("PATH", System.getenv("PATH") + ":" + System.getenv(XLearningConstants.Environment.USER_PATH.toString()));

    LOG.debug("env:" + containerEnv.toString());
    Set<String> envStr = containerEnv.keySet();
    for (String anEnvStr : envStr) {
      LOG.debug("env:" + anEnvStr);
    }
    return containerEnv;
  }

  private List<String> buildContainerLaunchCommand(int containerMemory) {
    List<String> containerLaunchcommands = new ArrayList<>();
    LOG.info("Setting up container command");
    Vector<CharSequence> vargs = new Vector<>(10);
    vargs.add("${JAVA_HOME}" + "/bin/java");
    vargs.add("-Xmx" + containerMemory + "m");
    vargs.add("-Xms" + containerMemory + "m");
    String javaOpts = conf.get(XLearningConfiguration.XLEARNING_CONTAINER_EXTRA_JAVA_OPTS, XLearningConfiguration.DEFAULT_XLEARNING_CONTAINER_JAVA_OPTS_EXCEPT_MEMORY);
    if (!StringUtils.isBlank(javaOpts)) {
      vargs.add(javaOpts);
    }
    vargs.add(XLearningContainer.class.getName());
    vargs.add("1>" + ApplicationConstants.LOG_DIR_EXPANSION_VAR + "/" + ApplicationConstants.STDOUT);
    vargs.add("2>" + ApplicationConstants.LOG_DIR_EXPANSION_VAR + "/" + ApplicationConstants.STDERR);

    StringBuilder containerCmd = new StringBuilder();
    for (CharSequence str : vargs) {
      containerCmd.append(str).append(" ");
    }
    containerLaunchcommands.add(containerCmd.toString());
    LOG.info("Container launch command: " + containerLaunchcommands.toString());
    return containerLaunchcommands;
  }

  /**
   * Async Method telling NMClientAsync to launch specific container
   *
   * @param container the container which should be launched
   * @return is launched success
   */
  @SuppressWarnings("deprecation")
  private void launchContainer(Map<String, LocalResource> containerLocalResource,
                               Map<String, String> containerEnv,
                               List<String> containerLaunchcommands,
                               Container container, int index) throws IOException {
    LOG.info("Setting up launch context for containerID="
        + container.getId());

    containerEnv.put(XLearningConstants.Environment.XLEARNING_TF_INDEX.toString(), String.valueOf(index));
    ContainerLaunchContext ctx = ContainerLaunchContext.newInstance(
        containerLocalResource, containerEnv, containerLaunchcommands, null, null, null);

    try {
      nmAsync.startContainerAsync(container, ctx);
    } catch (Exception e) {
      throw new RuntimeException("Launching container " + container.getId() + " failed!");
    }
  }

  private void appendMessage(String message, boolean logEnable) {
    if (logEnable) {
      LOG.info(message);
    }
    appendMessage(new Message(LogType.STDERR, message));
  }

  private void appendMessage(Message message) {
    if (applicationMessageQueue.size() >= conf.getInt(XLearningConfiguration.XLEARNING_MESSAGES_LEN_MAX, XLearningConfiguration.DEFAULT_XLEARNING_MESSAGES_LEN_MAX)) {
      applicationMessageQueue.poll();
    }
    if (!applicationMessageQueue.offer(message)) {
      LOG.warn("Message queue is full, this message will be ignored");
    }
  }

  private void unregisterApp(FinalApplicationStatus finalStatus, String diagnostics) {
    try {
      amrmAsync.unregisterApplicationMaster(finalStatus, diagnostics,
          applicationHistoryUrl);
      amrmAsync.stop();
    } catch (Exception e) {
      LOG.error("Error while unregister Application", e);
    }
  }

  public Configuration getConf() {
    return conf;
  }

  @SuppressWarnings("deprecation")
  private boolean run() throws IOException, NoSuchAlgorithmException {
    LOG.info("ApplicationMaster Starting ...");

    registerApplicationMaster();
    if (conf.get(XLearningConfiguration.XLEARNING_INPUT_STRATEGY, XLearningConfiguration.DEFAULT_XLEARNING_INPUT_STRATEGY).equals("STREAM")) {
      buildInputStreamFileStatus();
    } else {
      if(envs.get(XLearningConstants.Environment.USE_S3.toString()).equalsIgnoreCase("yes")){
        buildS3InputFileStatus();
      }else {
        buildInputFileStatus();
      }

    }

    if ("TENSORFLOW".equals(xlearningAppType) || "MXNET".equals(xlearningAppType)) {
      this.appendMessage("XLearning application needs " + workerNum + " worker and "
          + psNum + " ps  containers in fact", true);
    } else {
      this.appendMessage("XLearning application needs " + workerNum + " worker container in fact", true);
    }

    buildContainerRequest();

    rmCallbackHandler.setNeededPsContainersCount(psNum);
    rmCallbackHandler.setNeededWorkerContainersCount(workerNum);

    int allocateInterval = conf.getInt(XLearningConfiguration.XLEARNING_ALLOCATE_INTERVAL, XLearningConfiguration.DEFAULT_XLEARNING_ALLOCATE_INTERVAL);
    amrmAsync.setHeartbeatInterval(allocateInterval);

    for (int i = 0; i < psNum; i++) {
      amrmAsync.addContainerRequest(psContainerRequest);
    }

    if (("TENSORFLOW".equals(xlearningAppType) && !single) || ("MXNET".equals(xlearningAppType) && !singleMx)) {
      LOG.info("Try to allocate " + psNum + " ps/server containers");
    }

    Boolean startAllocatedContainer = false;
    Long startAllocatedTimeStamp = Long.MIN_VALUE;
    String failMessage = "Container waiting except the allocated expiry time. Maybe the Cluster available resources are not satisfied the user need. Please resubmit !";
    while (rmCallbackHandler.getAllocatedPsContainerNumber() < psNum) {
      List<Container> cancelContainers = rmCallbackHandler.getCancelContainer();
      List<String> blackHosts = rmCallbackHandler.getBlackHosts();
      try {
        Method updateBlacklist = amrmAsync.getClass().getMethod("updateBlacklist", List.class, List.class);
        updateBlacklist.invoke(amrmAsync, blackHosts, null);
      } catch (NoSuchMethodException e) {
        LOG.debug("current hadoop version don't have the method updateBlacklist of Class " + amrmAsync.getClass().toString() + ". For More Detail:" + e);
      } catch (InvocationTargetException e) {
        LOG.error("InvocationTargetException : " + e);
      } catch (IllegalAccessException e) {
        LOG.error("IllegalAccessException : " + e);
      }
      if (cancelContainers.size() != 0) {
        for (Container container : cancelContainers) {
          LOG.info("Canceling container: " + container.getId().toString());
          amrmAsync.releaseAssignedContainer(container.getId());
          amrmAsync.addContainerRequest(psContainerRequest);
        }
        cancelContainers.clear();
      }
      if (rmCallbackHandler.getAllocatedPsContainerNumber() > 0 && !startAllocatedContainer) {
        startAllocatedContainer = true;
        startAllocatedTimeStamp = System.currentTimeMillis();
      }
      if (startAllocatedContainer && (System.currentTimeMillis() - startAllocatedTimeStamp) > conf.getInt(YarnConfiguration.RM_CONTAINER_ALLOC_EXPIRY_INTERVAL_MS, YarnConfiguration.DEFAULT_RM_CONTAINER_ALLOC_EXPIRY_INTERVAL_MS)) {
        this.appendMessage(failMessage, true);
        this.appendMessage("Unregister  Application", true);
        unregisterApp(FinalApplicationStatus.FAILED, failMessage);
        return false;
      }
      Utilities.sleep(allocateInterval);
    }

    if (("TENSORFLOW".equals(xlearningAppType) && !single) || ("MXNET".equals(xlearningAppType) && !singleMx)) {
      LOG.info("Total " + rmCallbackHandler.getAllocatedPsContainerNumber() + " ps containers has allocated.");
    }

    rmCallbackHandler.setWorkerContainersAllocating();

    for (int i = 0; i < workerNum; i++) {
      amrmAsync.addContainerRequest(workerContainerRequest);
    }

    LOG.info("Try to allocate " + workerNum + " worker containers");

    while (rmCallbackHandler.getAllocatedWorkerContainerNumber() < workerNum) {
      List<Container> cancelContainers = rmCallbackHandler.getCancelContainer();
      List<String> blackHosts = rmCallbackHandler.getBlackHosts();
      try {
        Method updateBlacklist = amrmAsync.getClass().getMethod("updateBlacklist", List.class, List.class);
        updateBlacklist.invoke(amrmAsync, blackHosts, null);
      } catch (NoSuchMethodException e) {
        LOG.debug("current hadoop version don't have the method updateBlacklist of Class " + amrmAsync.getClass().toString() + ". For More Detail:" + e);
      } catch (InvocationTargetException e) {
        LOG.error("invoke the method updateBlacklist of Class " + amrmAsync.getClass().toString() + " InvocationTargetException Error : " + e);
      } catch (IllegalAccessException e) {
        LOG.error("invoke the method updateBlacklist of Class " + amrmAsync.getClass().toString() + " IllegalAccessException Error : " + e);
      }
      if (cancelContainers.size() != 0) {
        for (Container container : cancelContainers) {
          LOG.info("Canceling container: " + container.getId().toString());
          amrmAsync.releaseAssignedContainer(container.getId());
          amrmAsync.addContainerRequest(workerContainerRequest);
        }
        cancelContainers.clear();
      }
      if (rmCallbackHandler.getAllocatedWorkerContainerNumber() > 0 && !startAllocatedContainer) {
        startAllocatedContainer = true;
        startAllocatedTimeStamp = System.currentTimeMillis();
      }
      if (startAllocatedContainer && (System.currentTimeMillis() - startAllocatedTimeStamp) > conf.getInt(YarnConfiguration.RM_CONTAINER_ALLOC_EXPIRY_INTERVAL_MS, YarnConfiguration.DEFAULT_RM_CONTAINER_ALLOC_EXPIRY_INTERVAL_MS)) {
        this.appendMessage(failMessage, true);
        this.appendMessage("Unregister  Application", true);
        unregisterApp(FinalApplicationStatus.FAILED, failMessage);
        return false;
      }
      Utilities.sleep(allocateInterval);
    }

    acquiredPsContainers = rmCallbackHandler.getAcquiredPsContainer();
    acquiredWorkerContainers = rmCallbackHandler.getAcquiredWorkerContainer();

    int totalNumAllocatedWorkers = rmCallbackHandler.getAllocatedWorkerContainerNumber();
    if (totalNumAllocatedWorkers > workerNum) {
      while (acquiredWorkerContainers.size() > workerNum) {
        Container releaseContainer = acquiredWorkerContainers.remove(0);
        amrmAsync.releaseAssignedContainer(releaseContainer.getId());
        LOG.info("Release container " + releaseContainer.getId().toString());
      }
    }
    LOG.info("Total " + acquiredWorkerContainers.size() + " worker containers has allocated.");

    //launch mxnet scheduler
    if (xlearningAppType.equals("MXNET") && !singleMx) {
      LOG.info("Setting environments for the MXNet scheduler");
      dmlcPsRootUri = applicationMasterHostname;
      Socket schedulerReservedSocket = new Socket();
      try {
        schedulerReservedSocket.bind(new InetSocketAddress("127.0.0.1", 0));
      } catch (IOException e) {
        LOG.error("Can not get available port");
      }
      dmlcPsRootPort = schedulerReservedSocket.getLocalPort();
      String[] schedulerEnv = new String[]{
          "PATH=" + System.getenv("PATH"),
          "JAVA_HOME=" + System.getenv("JAVA_HOME"),
          "HADOOP_HOME=" + System.getenv("HADOOP_HOME"),
          "HADOOP_HDFS_HOME=" + System.getenv("HADOOP_HDFS_HOME"),
          "LD_LIBRARY_PATH=" + "./:" + System.getenv("LD_LIBRARY_PATH") + ":" + System.getenv("JAVA_HOME") +
              "/jre/lib/amd64/server:" + System.getenv("HADOOP_HOME") + "/lib/native",
          "CLASSPATH=" + "./:" + System.getenv("CLASSPATH") + ":" + System.getProperty("java.class.path"),
          "DMLC_ROLE=scheduler",
          "DMLC_PS_ROOT_URI=" + dmlcPsRootUri,
          "DMLC_PS_ROOT_PORT=" + dmlcPsRootPort,
          XLearningConstants.Environment.XLEARNING_MXNET_WORKER_NUM.toString() + "=" + workerNum,
          XLearningConstants.Environment.XLEARNING_MXNET_SERVER_NUM.toString() + "=" + psNum,
          "PYTHONUNBUFFERED=1"
      };
      LOG.info("Executing command:" + xlearningCommand);
      LOG.info("DMLC_PS_ROOT_URI is " + dmlcPsRootUri);
      LOG.info("DMLC_PS_ROOT_PORT is " + dmlcPsRootPort);
      LOG.info(XLearningConstants.Environment.XLEARNING_MXNET_WORKER_NUM.toString() + "=" + workerNum);
      LOG.info(XLearningConstants.Environment.XLEARNING_MXNET_SERVER_NUM.toString() + "=" + psNum);

      try {
        Runtime rt = Runtime.getRuntime();
        schedulerReservedSocket.close();
        final Process mxnetSchedulerProcess = rt.exec(xlearningCommand, schedulerEnv);
        LOG.info("Starting thread to redirect stdout of MXNet scheduler process");
        Thread mxnetSchedulerRedirectThread = new Thread(new Runnable() {
          @Override
          public void run() {
            try {
              BufferedReader reader;
              reader = new BufferedReader(new InputStreamReader(mxnetSchedulerProcess.getInputStream()));
              String mxnetSchedulerStdoutLog;
              while ((mxnetSchedulerStdoutLog = reader.readLine()) != null) {
                LOG.info(mxnetSchedulerStdoutLog);
              }
            } catch (Exception e) {
              LOG.warn("Exception in thread mxnetSchedulerRedirectThread");
              e.printStackTrace();
            }
          }
        });
        mxnetSchedulerRedirectThread.start();

        LOG.info("Starting thread to redirect stderr of MXNet scheduler process");
        Thread boardStderrRedirectThread = new Thread(new Runnable() {
          @Override
          public void run() {
            try {
              BufferedReader reader;
              reader = new BufferedReader(new InputStreamReader(mxnetSchedulerProcess.getErrorStream()));
              String mxnetSchedulerStderrLog;
              while ((mxnetSchedulerStderrLog = reader.readLine()) != null) {
                LOG.debug(mxnetSchedulerStderrLog);
              }
            } catch (Exception e) {
              LOG.warn("Error in thread mxnetSchedulerStderrRedirectThread");
              e.printStackTrace();
            }
          }
        });
        boardStderrRedirectThread.start();
      } catch (Exception e) {
        LOG.error("start MXNet scheduler error " + e);
      }

    }

    //launch dist xgboost scheduler
    if (xlearningAppType.equals("DISTXGBOOST")) {
      LOG.info("Seting environments for the dist xgboost scheduler");
      dmlcTrackerUri = applicationMasterHostname;
      Socket schedulerReservedSocket = new Socket();
      try {
        schedulerReservedSocket.bind(new InetSocketAddress("127.0.0.1", 0));
      } catch (IOException e) {
        LOG.error("Can not get available port");
      }
      dmlcTrackerPort = schedulerReservedSocket.getLocalPort();
      String[] schedulerEnv = new String[]{
          "PATH=" + System.getenv("PATH"),
          "JAVA_HOME=" + System.getenv("JAVA_HOME"),
          "HADOOP_HOME=" + System.getenv("HADOOP_HOME"),
          "HADOOP_HDFS_HOME=" + System.getenv("HADOOP_HDFS_HOME"),
          "LD_LIBRARY_PATH=" + "./:" + System.getenv("LD_LIBRARY_PATH") + ":" + System.getenv("JAVA_HOME") +
              "/jre/lib/amd64/server:" + System.getenv("HADOOP_HOME") + "/lib/native",
          "CLASSPATH=" + "./:" + System.getenv("CLASSPATH") + ":" + System.getProperty("java.class.path"),
          "PYTHONUNBUFFERED=1"
      };
      String distXgboostSchedulerCmd = "python xgboost/self-define/rabitTracker.py --num-workers=" + workerNum
          + " --host-ip=" + dmlcTrackerUri + " --port=" + dmlcTrackerPort;
      LOG.info("Dist xgboost scheduler executing command:" + distXgboostSchedulerCmd);
      LOG.info("DMLC_TRACKER_URI is " + dmlcTrackerUri);
      LOG.info("DMLC_TRACKER_PORT is " + dmlcTrackerPort);
      LOG.info("DMLC_NUM_WORKER=" + workerNum);

      try {
        Runtime rt = Runtime.getRuntime();
        schedulerReservedSocket.close();
        final Process xgboostSchedulerProcess = rt.exec(distXgboostSchedulerCmd, schedulerEnv);
        LOG.info("Starting thread to redirect stdout of xgboost scheduler process");
        Thread xgboostSchedulerRedirectThread = new Thread(new Runnable() {
          @Override
          public void run() {
            try {
              BufferedReader reader;
              reader = new BufferedReader(new InputStreamReader(xgboostSchedulerProcess.getInputStream()));
              String xgboostSchedulerStdoutLog;
              while ((xgboostSchedulerStdoutLog = reader.readLine()) != null) {
                LOG.info(xgboostSchedulerStdoutLog);
              }
            } catch (Exception e) {
              LOG.warn("Exception in thread xgboostSchedulerRedirectThread");
              e.printStackTrace();
            }
          }
        });
        xgboostSchedulerRedirectThread.start();

        LOG.info("Starting thread to redirect stderr of xgboost scheduler process");
        Thread xgboostSchedulerStderrRedirectThread = new Thread(new Runnable() {
          @Override
          public void run() {
            try {
              BufferedReader reader;
              reader = new BufferedReader(new InputStreamReader(xgboostSchedulerProcess.getErrorStream()));
              String xgboostSchedulerStderrLog;
              while ((xgboostSchedulerStderrLog = reader.readLine()) != null) {
                LOG.info(xgboostSchedulerStderrLog);
              }
            } catch (Exception e) {
              LOG.warn("Error in thread xgboostSchedulerStderrRedirectThread");
              e.printStackTrace();
            }
          }
        });
        xgboostSchedulerStderrRedirectThread.start();

      } catch (Exception e) {
        LOG.info("start xgboost scheduler error " + e);
      }

    }



    if (conf.get(XLearningConfiguration.XLEARNING_INPUT_STRATEGY, XLearningConfiguration.DEFAULT_XLEARNING_INPUT_STRATEGY).equals("STREAM")) {
      allocateInputStreamSplits();
    } else {
      if(envs.get(XLearningConstants.Environment.USE_S3.toString()).equalsIgnoreCase("yes")){
        allocateS3InputSplits();
      }else {
        allocateInputSplits();
      }

    }

    buildOutputLocations();
    buildContainerLocalResource();
    Map<String, String> workerContainerEnv = buildContainerEnv(XLearningConstants.WORKER);
    Map<String, String> psContainerEnv = buildContainerEnv(XLearningConstants.PS);
    List<String> workerContainerLaunchCommands = buildContainerLaunchCommand(workerMemory);
    List<String> psContainerLaunchCommands = buildContainerLaunchCommand(psMemory);

    LOG.info("Launching containers");
    int index = 0;
    for (Container container : acquiredPsContainers) {
      LOG.info("Launching ps container " + container.getId()
          + " on " + container.getNodeId().getHost() + ":" + container.getNodeId().getPort());

      //TODO launch container in special thread take with fault-tolerant
      launchContainer(containerLocalResource, psContainerEnv,
          psContainerLaunchCommands, container, index++);
      containerListener.registerContainer(new XLearningContainerId(container.getId()), XLearningConstants.PS);
    }
    index = 0;
    for (Container container : acquiredWorkerContainers) {
      LOG.info("Launching worker container " + container.getId()
          + " on " + container.getNodeId().getHost() + ":" + container.getNodeId().getPort());

      //TODO launch container in special thread take with fault-tolerant
      launchContainer(containerLocalResource, workerContainerEnv,
          workerContainerLaunchCommands, container, index++);
      containerListener.registerContainer(new XLearningContainerId(container.getId()), XLearningConstants.WORKER);
    }

    String diagnostics = "";
    boolean finalSuccess;

    if (this.applicationContext.getOutputs().size() > 0) {
      Thread saveInnerModelMonitor = new Thread(new Runnable() {
        @Override
        public void run() {
          while (true) {
            try {
              Boolean startSaved = applicationContext.getStartSavingStatus();
              containerListener.setSaveInnerModel(startSaved);
              while (startSaved) {
                if (containerListener.interResultCompletedNum(containerListener.interResultTimeStamp())
                    == containerListener.getInnerSavingContainerNum()) {
                  lastSavingStatus = true;
                  if (!savingModelList.contains(containerListener.interResultTimeStamp())) {
                    savingModelList.add(containerListener.interResultTimeStamp());
                  }
                  break;
                }
                Utilities.sleep(conf.getInt(XLearningConfiguration.XLEARNING_CONTAINER_HEARTBEAT_INTERVAL, XLearningConfiguration.DEFAULT_XLEARNING_CONTAINER_HEARTBEAT_INTERVAL));
              }
            } catch (Exception e) {
              LOG.error("Monitor the InnerModel saving error: " + e);
            }
          }
        }
      });
      saveInnerModelMonitor.start();
    }

    try {
      LOG.info("Waiting for train completed");
      Map<XLearningContainerId, XLearningContainerStatus> lastWorkerContainerStatus = new ConcurrentHashMap<>();
      Map<XLearningContainerId, XLearningContainerStatus> lastPsContainerStatus = new ConcurrentHashMap<>();
      while (!containerListener.isTrainCompleted()) {
        //report progress to client
        if(conf.getBoolean(XLearningConfiguration.XLEARNING_REPORT_CONTAINER_STATUS, XLearningConfiguration.DEFAULT_XLEARNING_REPORT_CONTAINER_STATUS)) {
          List<Container> workerContainersStatus = applicationContext.getWorkerContainers();
          List<Container> psContainersStatus = applicationContext.getPsContainers();
          for(Container container : workerContainersStatus) {
            if(!lastWorkerContainerStatus.containsKey(new XLearningContainerId(container.getId()))) {
              lastWorkerContainerStatus.put(new XLearningContainerId(container.getId()), XLearningContainerStatus.STARTED);
            }
            if(!applicationContext.getContainerStatus(new XLearningContainerId(container.getId())).equals(lastWorkerContainerStatus.get(new XLearningContainerId(container.getId())))) {
              this.appendMessage("container " + container.getId().toString() + " status is " + applicationContext.getContainerStatus(new XLearningContainerId(container.getId())), false);
              lastWorkerContainerStatus.put(new XLearningContainerId(container.getId()),applicationContext.getContainerStatus(new XLearningContainerId(container.getId())));
            }
          }
          for(Container container : psContainersStatus) {
            if(!lastPsContainerStatus.containsKey(new XLearningContainerId(container.getId()))) {
              lastPsContainerStatus.put(new XLearningContainerId(container.getId()), XLearningContainerStatus.STARTED);
            }
            if(!applicationContext.getContainerStatus(new XLearningContainerId(container.getId())).equals(lastPsContainerStatus.get(new XLearningContainerId(container.getId())))) {
              this.appendMessage("container " + container.getId().toString() + " status is " + applicationContext.getContainerStatus(new XLearningContainerId(container.getId())), false);
              lastPsContainerStatus.put(new XLearningContainerId(container.getId()),applicationContext.getContainerStatus(new XLearningContainerId(container.getId())));
            }
          }
        }
        List<Container> workerContainers = applicationContext.getWorkerContainers();
        Map<XLearningContainerId, String> clientProgress = applicationContext.getReporterProgress();
        float total = 0.0f;
        for (Container container : workerContainers) {
          String progressLog = clientProgress.get(new XLearningContainerId(container.getId()));
          if (progressLog != null && !progressLog.equals("")) {
            String[] progress = progressLog.toString().split(":");
            if (progress.length != 2) {
              this.appendMessage("progress log format error", false);
            } else {
              try {
                Float percentProgress = Float.parseFloat(progress[1]);
                if (percentProgress < 0.0 || percentProgress > 1.0) {
                  this.appendMessage("progress log format error", false);
                } else {
                  total += Float.parseFloat(progress[1]);
                }
              } catch (Exception e) {
                this.appendMessage("progress log format error", false);
              }
            }
          }
        }
        if (total > 0.0f) {
          float finalProgress = total / workerContainers.size();
          DecimalFormat df = new DecimalFormat("0.00");
          df.setRoundingMode(RoundingMode.HALF_UP);
          this.appendMessage("reporter progress:" + df.format(finalProgress * 100) + "%", false);
          rmCallbackHandler.setProgress(finalProgress);
        }
        Utilities.sleep(statusUpdateInterval);
      }
      LOG.info("Train completed");
      containerListener.setTrainFinished();

      if (("TENSORFLOW".equals(xlearningAppType) && !single) || ("MXNET".equals(xlearningAppType) && !singleMx)) {
        LOG.info("Waiting all ps containers completed");
        while (!containerListener.isAllPsContainersFinished()) {
          Utilities.sleep(statusUpdateInterval);
        }
        LOG.info("All ps/server containers completed");
      }

      finalSuccess = containerListener.isAllWorkerContainersSucceeded();
      if (finalSuccess) {
        if ((conf.get(XLearningConfiguration.XLEARNING_OUTPUT_STRATEGY, XLearningConfiguration.DEFAULT_XLEARNING_OUTPUT_STRATEGY).equals("STREAM")) && outputInfos.size() > 0) {
          LOG.info("XLEARNING_OUTPUT_STRATEGY is STREAM, AM handling the final result...");
          FileSystem fs = new Path(outputInfos.get(0).getDfsLocation()).getFileSystem(conf);
          Map<XLearningContainerId, String> mapPath = applicationContext.getMapedTaskID();
          for (Container finishedContainer : acquiredWorkerContainers) {
            String taskID = mapPath.get(new XLearningContainerId(finishedContainer.getId()));
            Path tmpResultPath = new Path(outputInfos.get(0).getDfsLocation() + "/_temporary/" + finishedContainer.getId().toString()
                + "/_temporary/0/_temporary/" + taskID);
            LOG.info("tmpResultPath is " + tmpResultPath.toString());
            Path finalResultPath = new Path(outputInfos.get(0).getDfsLocation() + "/" + finishedContainer.getId().toString());
            LOG.info("finalResultPath is " + finalResultPath.toString());
            if (fs.exists(tmpResultPath)) {
              LOG.info("Move from " + tmpResultPath.toString() + " to " + finalResultPath.toString());
              fs.rename(tmpResultPath, finalResultPath);
            }
          }
          Path tmpPath = new Path(outputInfos.get(0).getDfsLocation() + "/_temporary/");
          if (fs.exists(tmpPath)) {
            fs.delete(tmpPath, true);
          }
          fs.createNewFile(new Path(outputInfos.get(0).getDfsLocation() + "/_SUCCESS"));
          fs.close();
        } else {
          for (OutputInfo outputInfo : outputInfos) {
            FileSystem fs = new Path(outputInfo.getDfsLocation()).getFileSystem(conf);
            Path finalResultPath = new Path(outputInfo.getDfsLocation());
            for (Container finishedContainer : acquiredWorkerContainers) {
              Path tmpResultPath = new Path(outputInfo.getDfsLocation() + "/_temporary/" + finishedContainer.getId().toString());
              if (fs.exists(tmpResultPath)) {
                LOG.info("Move from " + tmpResultPath.toString() + " to " + finalResultPath);
                fs.rename(tmpResultPath, finalResultPath);
              }
            }
            Path tmpPath = new Path(outputInfo.getDfsLocation() + "/_temporary/");
            if (fs.exists(tmpPath)) {
              fs.delete(tmpPath, true);
            }
            fs.createNewFile(new Path(outputInfo.getDfsLocation() + "/_SUCCESS"));
            fs.close();
          }
        }
      }
    } catch (Exception e) {
      finalSuccess = false;
      this.appendMessage("Some error occurs"
          + org.apache.hadoop.util.StringUtils.stringifyException(e), true);
      diagnostics = e.getMessage();
    }

    int appAttempts = conf.getInt(XLearningConfiguration.XLEARNING_APP_MAX_ATTEMPTS, XLearningConfiguration.DEFAULT_XLEARNING_APP_MAX_ATTEMPTS);

    if (appAttempts > conf.getInt(YarnConfiguration.RM_AM_MAX_ATTEMPTS, YarnConfiguration.DEFAULT_RM_AM_MAX_ATTEMPTS)) {
      appAttempts = conf.getInt(YarnConfiguration.RM_AM_MAX_ATTEMPTS, YarnConfiguration.DEFAULT_RM_AM_MAX_ATTEMPTS);
    }

    if (!finalSuccess && applicationAttemptID.getAttemptId() < appAttempts) {
      Runtime.getRuntime().removeShutdownHook(cleanApplication);
      throw new RuntimeException("Application Failed, retry starting. Note that container memory will auto scale if user config the setting.");
    }

    this.appendMessage("Unregistered Application", true);
    unregisterApp(finalSuccess ? FinalApplicationStatus.SUCCEEDED
        : FinalApplicationStatus.FAILED, diagnostics);

    return finalSuccess;
  }

  /**
   * Internal class for running application class
   */
  private class RunningAppContext implements ApplicationContext {

    @Override
    public ApplicationId getApplicationID() {
      return applicationAttemptID.getApplicationId();
    }

    @Override
    public int getWorkerNum() {
      return workerNum;
    }

    @Override
    public int getPsNum() {
      return psNum;
    }

    @Override
    public List<Container> getWorkerContainers() {
      return acquiredWorkerContainers;
    }

    @Override
    public List<Container> getPsContainers() {
      return acquiredPsContainers;
    }

    @Override
    public XLearningContainerStatus getContainerStatus(XLearningContainerId containerId) {
      return containerListener.getContainerStatus(containerId);
    }

    @Override
    public LinkedBlockingQueue<Message> getMessageQueue() {
      return applicationMessageQueue;
    }

    @Override
    public List<InputInfo> getInputs(XLearningContainerId containerId) {
      if (!containerId2InputInfo.containsKey(containerId)) {
        LOG.info("containerId2InputInfo not contains" + containerId.getContainerId());
        return new ArrayList<InputInfo>();
      }
      return containerId2InputInfo.get(containerId);
    }
    @Override
    public S3InputInfo getS3Input(XLearningContainerId containerId){

      if (!containerId2S3InputInfo.containsKey(containerId)) {
        LOG.info("containerId2S3InputInfo not contains" + containerId.getContainerId());
        return new  S3InputInfo();
      }
      return containerId2S3InputInfo.get(containerId) ;

    }

    @Override
    public List<InputSplit> getStreamInputs(XLearningContainerId containerId) {
      if (!containerId2InputSplit.containsKey(containerId)) {
        LOG.info("containerId2InputSplit not contains" + containerId.getContainerId());
        return new ArrayList<InputSplit>();
      }
      return containerId2InputSplit.get(containerId);
    }

    @Override
    public List<OutputInfo> getOutputs() {
      return outputInfos;
    }

    @Override
    public String getTensorBoardUrl() {
      return containerListener.getTensorboardUrl();
    }

    @Override
    public Map<XLearningContainerId, String> getReporterProgress() {
      return containerListener.getReporterProgress();
    }

    @Override
    public Map<XLearningContainerId, String> getContainersAppStartTime() {
      return containerListener.getContainersAppStartTime();
    }

    @Override
    public Map<XLearningContainerId, String> getContainersAppFinishTime() {
      return containerListener.getContainersAppFinishTime();
    }

    @Override
    public Map<XLearningContainerId, String> getMapedTaskID() {
      return containerListener.getMapedTaskID();
    }

    @Override
    public Map<XLearningContainerId, ConcurrentHashMap<String, LinkedBlockingDeque<Object>>> getContainersCpuMetrics() {
      return containerListener.getContainersCpuMetrics();
    }

    @Override
    public int getSavingModelStatus() {
      return containerListener.interResultCompletedNum(containerListener.interResultTimeStamp());
    }

    @Override
    public Boolean getStartSavingStatus() {
      return startSavingModel;
    }

    @Override
    public int getSavingModelTotalNum() {
      return containerListener.getInnerSavingContainerNum();
    }

    @Override
    public void startSavingModelStatus(Boolean flag) {
      LOG.info("current savingModelStatus is " + flag);
      startSavingModel = flag;
    }

    @Override
    public Boolean getLastSavingStatus() {
      return lastSavingStatus;
    }

    @Override
    public List<Long> getModelSavingList() {
      return savingModelList;
    }

  }

  /**
   * @param args Command line args
   */
  public static void main(String[] args) {
    ApplicationMaster appMaster;
    try {
      appMaster = new ApplicationMaster();
      appMaster.init();
      if (appMaster.run()) {
        LOG.info("Application completed successfully.");
        System.exit(0);
      } else {
        LOG.info("Application failed.");
        System.exit(1);
      }
    } catch (Exception e) {
      LOG.fatal("Error running ApplicationMaster", e);
      System.exit(1);
    }
  }

}
