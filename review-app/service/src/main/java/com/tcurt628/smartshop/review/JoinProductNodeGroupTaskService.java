package com.tcurt628.smartshop.review;

import com.tcurt628.smartshop.dns.SmartShopDnsQueries;
import com.vmware.xenon.common.FactoryService;
import com.vmware.xenon.common.Operation;
import com.vmware.xenon.common.Service;
import com.vmware.xenon.common.ServiceDocumentDescription;
import com.vmware.xenon.common.TaskState;
import com.vmware.xenon.common.UriUtils;
import com.vmware.xenon.services.common.NodeGroupService;
import com.vmware.xenon.services.common.NodeState;
import org.apache.commons.lang3.StringUtils;

import java.net.URI;
import java.util.EnumSet;
import java.util.logging.Level;
import java.util.logging.Logger;

import static com.tcurt628.smartshop.review.ReviewHost.PRODUCT_NODE_GROUP_URI;
import static com.tcurt628.smartshop.review.ReviewService.PRODUCT_FACTORY_LINK;

/**
 * This task is responsible for joining the {@code product} node group as an {@code OBSERVER}.
 * This class was heavily influenced by the <a href="https://github.com/vmware/xenon/wiki/Task-Service-Tutorial">
 * Task Service Tutorial</a> on the <a href="https://github.com/vmware/xenon/wiki">Xenon wiki
 * page.</a>
 */
public class JoinProductNodeGroupTaskService extends AbstractTaskService<JoinProductNodeGroupTaskService.JoinProductNodeGroupTaskServiceState> {

   public static final String FACTORY_LINK = "/review-tasks/join-product-node-group";
   private static final Integer DEFAULT_QUORUM_IF_UNKNOWN = 1;

   private static SmartShopDnsQueries dnsQueries = null;
   private static Integer quorum = null;

   /** Not crazy about this, but it works for now I guess... */
   private static Logger logger = Logger.getLogger(JoinProductNodeGroupTaskService.class.getName());
   static {
      logger.setLevel(Level.FINE);
   }

   public enum SubStage {
      FINDING_PRODUCT_PEER_NODE,
      JOINING_AS_OBSERVER
   }

   public static class JoinProductNodeGroupTaskServiceState extends AbstractTaskService.BaseTaskServiceState {

      /** The current substage of the task. See {@link SubStage} */
      @UsageOption(option = ServiceDocumentDescription.PropertyUsageOption.AUTO_MERGE_IF_NOT_NULL)
      public SubStage subStage;

      /** The URI (only host:port) of the host running the {@code product} node group that we should join. */
      @UsageOption(option = ServiceDocumentDescription.PropertyUsageOption.AUTO_MERGE_IF_NOT_NULL)
      public URI productHostUriToJoin;

      @Override
      public String toString() {
         String taskInfoStage = taskInfo != null && taskInfo.stage != null ? taskInfo.stage.toString() : null;
         String subStageString = subStage != null ? subStage.toString() : null;
         String hostUri = productHostUriToJoin != null ? productHostUriToJoin.toString() : null;
         return String.format("JoinProductNodeGroupTaskServiceState: [taskInfo.stage=%s] [subStage=%s] [productHostUriToJoin=%s]",
               taskInfoStage, subStageString, hostUri);
      }
   }

   /** Create a default factory service that starts instances of this service on POST. */
   public static Service createFactory() {
      return FactoryService.create(JoinProductNodeGroupTaskService.class, JoinProductNodeGroupTaskServiceState.class);
   }

   public JoinProductNodeGroupTaskService() {
      super(JoinProductNodeGroupTaskServiceState.class);
      toggleOption(ServiceOption.PERSISTENCE, true);
      toggleOption(ServiceOption.REPLICATION, true);
      toggleOption(ServiceOption.INSTRUMENTATION, true);
      toggleOption(ServiceOption.OWNER_SELECTION, true);
      super.setPeerNodeSelectorPath(ReviewHost.REVIEW_NODE_SELECTOR_URI);

      if (dnsQueries == null || quorum == null) {
         ReviewHost reviewHost = (ReviewHost) getHost();
         dnsQueries = new SmartShopDnsQueries(reviewHost.hostArguments.dnshost, reviewHost.hostArguments.dnsport);
         quorum = reviewHost.hostArguments.initialNodes;
      }

      if (quorum == null) {
         quorum = DEFAULT_QUORUM_IF_UNKNOWN;
      }
   }

   /** Ensure that the input task is valid. */
   @Override
   protected JoinProductNodeGroupTaskServiceState validateStartPost(Operation taskOperation) {
      JoinProductNodeGroupTaskServiceState taskState = super.validateStartPost(taskOperation);

      if (taskState.subStage != null) {
         taskOperation.fail(new IllegalArgumentException("Do not specify subStage: internal use only"));
         return null;
      }

      return taskState;
   }

   /** Initialize the task. */
   @Override
   protected void initializeState(JoinProductNodeGroupTaskServiceState task, Operation taskOperation) {
      super.initializeState(task, taskOperation);
      task.subStage = SubStage.FINDING_PRODUCT_PEER_NODE;
   }

   /** All work happens through a {@code PATCH}, which is handled here. */
   @Override
   public void handlePatch(Operation patch) {
      JoinProductNodeGroupTaskServiceState currentTask = getState(patch);
      JoinProductNodeGroupTaskServiceState patchBody = getBody(patch);
      logFine("handlePatch():\n[currentTask=%s]\n[patchBody=%s]",
            currentTask != null ? currentTask: "", patchBody != null ? patchBody : "");

      if (!validateTransition(patch, currentTask, patchBody)) {
         return;
      }
      updateState(currentTask, patchBody);
      patch.complete();

      switch (patchBody.taskInfo.stage) {
      case STARTED:
         handleSubstage(patchBody);
         break;
      case FINISHED:
         logInfo("Task finished successfully");
         break;
      case FAILED:
         logWarning("Task failed: %s", StringUtils.defaultString(patchBody.failureMessage, "No reason given"));
         break;
      default:
         logWarning("Unexpected stage: %s", patchBody.taskInfo.stage);
         break;
      }
   }

   private void handleSubstage(JoinProductNodeGroupTaskServiceState task) {
      switch (task.subStage) {
      case FINDING_PRODUCT_PEER_NODE:
         handleFindProductPeerNode(task);
         break;
      case JOINING_AS_OBSERVER:
         handleJoinPeerAsObserver(task);
         break;
      default:
         logWarning("Unexpected sub stage: %s", task.subStage);
         break;
      }
   }

   /** Validate that the PATCH we got requests reasonable changes to our state */
   @Override
   protected boolean validateTransition(Operation patch, JoinProductNodeGroupTaskServiceState currentTask,
         JoinProductNodeGroupTaskServiceState patchBody) {
      super.validateTransition(patch, currentTask, patchBody);

      if (patchBody.taskInfo.stage == TaskState.TaskStage.STARTED && patchBody.subStage == null) {
         patch.fail(new IllegalArgumentException("Missing substage"));
         return false;
      }

      if (currentTask.taskInfo != null && currentTask.taskInfo.stage != null) {
         if (currentTask.taskInfo.stage == TaskState.TaskStage.STARTED && patchBody.taskInfo.stage == TaskState.TaskStage.STARTED) {
            if (currentTask.subStage.ordinal() > patchBody.subStage.ordinal()) {
               patch.fail(new IllegalArgumentException("Task substage cannot move backwards"));
               return false;
            }
         }
      }

      if (patchBody.subStage != null && patchBody.subStage == SubStage.JOINING_AS_OBSERVER &&
            patchBody.productHostUriToJoin == null) {
         patch.fail(new IllegalArgumentException("Cannot JOIN because the host URI to join has not been set"));
         return false;
      }

      return true;
   }

   /**
    * <p>Handle SubStage {@link SubStage#FINDING_PRODUCT_PEER_NODE}.</p>
    *
    * <p>
    * TODO - flush out details
    * </p>
    */
   private void handleFindProductPeerNode(JoinProductNodeGroupTaskServiceState task) {
      String productServiceDNSLookupQuery = String.format("$filter=serviceLink eq '%s'", PRODUCT_FACTORY_LINK);

      Operation.CompletionHandler handler = (op, ex) -> {
         if (ex != null) {
            String message = String.format("[URI=%s] does not exist when using GET provided by DNS lookup", PRODUCT_FACTORY_LINK);
            logInfo(message);
            sendSelfFailurePatch(task, message); // could add sleep/retry logic here...
            return;
         }

         String host = op.getUri().getHost();
         int port = op.getUri().getPort();
         URI uri = UriUtils.buildUri(host, port, null, null);
         task.productHostUriToJoin = uri;
         logInfo("Found host that owns the product node group that we should join: %s. Patching back to task to update subStage...",
               task.productHostUriToJoin);
         sendSelfPatch(task, TaskState.TaskStage.STARTED, SubStage.JOINING_AS_OBSERVER);
      };

      dnsQueries.queryDns(productServiceDNSLookupQuery, PRODUCT_FACTORY_LINK, getHost(), handler);
   }

   /**
    * <p>Handle SubStage {@link SubStage#JOINING_AS_OBSERVER}.</p>
    *
    * <p>
    * TODO - flush out details
    * </p>
    */
   private void handleJoinPeerAsObserver(JoinProductNodeGroupTaskServiceState task) {
      URI memberGroupUri = UriUtils.buildUri(task.productHostUriToJoin, PRODUCT_NODE_GROUP_URI);
      NodeGroupService.JoinPeerRequest joinPeerRequest = NodeGroupService.JoinPeerRequest.create(memberGroupUri, quorum);
      joinPeerRequest.localNodeOptions = EnumSet.of(NodeState.NodeOption.OBSERVER);

      Operation postToJoinNodeGroupAsObserver = Operation.createPost(this, PRODUCT_NODE_GROUP_URI)
            .setReferer(getHost().getUri())
            .setBody(joinPeerRequest)
            .setCompletion((completedOp, failure) -> {
               if (failure != null) {
                  String message = String.format("Error when trying to join node group. [op=%s] [ex=%s]",
                        completedOp, failure.getMessage());
                  logSevere(failure);
                  sendSelfFailurePatch(task, message);
                  return;
               }

               logInfo("Successfully joined [%s] as OBSERVER", memberGroupUri);
               sendSelfPatch(task, TaskState.TaskStage.FINISHED, null);
            });

      getHost().sendRequest(postToJoinNodeGroupAsObserver);
   }


   /** Send ourselves a PATCH that will indicate failure */
   private void sendSelfFailurePatch(JoinProductNodeGroupTaskServiceState task, String failureMessage) {
      task.failureMessage = failureMessage;
      sendSelfPatch(task, TaskState.TaskStage.FAILED, null);
   }

   /**
    * Send ourselves a PATCH that will advance to another step in the task workflow to the
    * specified stage and substage.
    */
   private void sendSelfPatch(JoinProductNodeGroupTaskServiceState task, TaskState.TaskStage stage, SubStage subStage) {
      if (task.taskInfo == null) {
         task.taskInfo = new TaskState();
      }
      task.taskInfo.stage = stage;
      task.subStage = subStage;
      sendSelfPatch(task);
   }

}
