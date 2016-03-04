package com.tcurt628.smartshop.review;

import com.tcurt628.smartshop.model.Product;
import com.vmware.xenon.common.*;
import com.vmware.xenon.services.common.QueryTask;
import com.vmware.xenon.services.common.ServiceUriPaths;
import org.apache.commons.lang3.StringUtils;

import java.net.URI;

/**
 * Created by tcurtis on 2/24/16.
 */
public class ReviewService extends StatefulService {

   public static final Integer MIN_STAR = 1;
   public static final Integer MAX_STAR = 5;

   public static final String FACTORY_LINK = "/reviews";

   /**
    * Create a default factory service that starts instances of this service on POST.
    */
   public static Service createFactory() {
      return FactoryService.create(ReviewService.class, ReviewServiceState.class);
   }

   public ReviewService() {
      super(ReviewServiceState.class);
      super.toggleOption(ServiceOption.PERSISTENCE, true);
      super.toggleOption(ServiceOption.REPLICATION, true);
      super.setPeerNodeSelectorPath(ReviewHost.REVIEW_NODE_SELECTOR_URI);
   }

   public static class ReviewServiceState extends ServiceDocument {
      public String productLink;
      public String author;
      public String content;
      public Integer stars;

      @Override
      public String toString() {
         return String.format("ReviewServiceState: [productLink=%s] [author=%s] [content=%s] [stars=%d]",
               productLink, author, content, stars);
      }
   }

   public static class ReviewUpdateRequest {
      public String content;
      public Integer stars;
   }

   @Override
   public void handleStart(Operation post) {
      try {
         validateState(post);
         post.complete();
         logInfo("handleStart() completed successfully. [post=%s]", post);
      } catch (Exception e) {
         logWarning("handleStart() FAILED! [post: %s] [error: %s]", post, e);
         post.fail(e);
      }
   }

   private void validateStars(Integer stars) {
      if (stars == null || stars < MIN_STAR || stars > MAX_STAR) {
         throw new IllegalArgumentException(
               String.format("stars: must be provided and be between %d and %d", MIN_STAR, MAX_STAR));
      }
   }

   private void validateContent(String content) {
      if (StringUtils.isEmpty(content)) {
         throw new IllegalArgumentException("content: cannot be empty");
      }
   }

   private void validateState(Operation post) {
      if (!post.hasBody()) {
         throw new IllegalArgumentException("Must include non-empty body");
      }

      ReviewServiceState state = post.getBody(ReviewServiceState.class);
      logWarning("Processing state: %s", state);

      validateStars(state.stars);
      validateContent(state.content);

      // Ensure that the product exists
      if (StringUtils.isEmpty(state.productLink)) {
         throw new IllegalArgumentException("productLink cannot be empty");
      }

      QueryTask.Query productQuery = QueryTask.Query.Builder.create()
            .addFieldClause(ServiceDocument.FIELD_NAME_KIND, "com:tcurt628:smartshop:product:ProductService:ProductServiceState")
            .addFieldClause(ServiceDocument.FIELD_NAME_SELF_LINK, state.productLink)
            .build();

      QueryTask queryTask = QueryTask.Builder.createDirectTask()
            .setQuery(productQuery)
            .build();

      URI queryTaskUri = UriUtils.buildUri(this.getHost(), ServiceUriPaths.CORE_QUERY_TASKS);

      // This will create the POST that corresponds to the query-user-1.post, above
      Operation postQuery = Operation.createPost(queryTaskUri)
            .setBody(queryTask)
            .setReferer(post.getReferer())
            .setCompletion((op, ex) -> {
               if (ex != null) {
                 throw new IllegalArgumentException("Error looking up productLink: " + state.productLink, ex);
               }

               // This examines the response from the Query Task Service
               // In our case, we decide a user exists if there's at least
               // one user in the response.
               QueryTask queryResponse = op.getBody(QueryTask.class);
               if (queryResponse.results.documentLinks != null
                     && queryResponse.results.documentLinks.isEmpty()) {
                  throw new IllegalArgumentException("No product found: " + state.productLink);
               }

               logInfo("Found valid product! [results.documentLinks=%s]", queryResponse.results.documentLinks);
            });
      sendRequest(postQuery);
   }

   @Override
   public void handlePatch(Operation patch) {
      logFine("handlePatch(). [patch=%s]", patch);
      ReviewServiceState currentState = getState(patch);
      ReviewUpdateRequest updateRequest = patch.getBody(ReviewUpdateRequest.class);

      try {
         validateStars(updateRequest.stars);
         validateContent(updateRequest.content);

         currentState.stars = updateRequest.stars;
         currentState.content = updateRequest.content;
      } catch (Exception e) {
         logWarning("Error during PATCH. [patch=%s] [error=%s]", patch, e);
         patch.fail(e);
         return;
      }

      setState(patch, currentState);
      patch.setBody(currentState);
      patch.complete();
   }
}
