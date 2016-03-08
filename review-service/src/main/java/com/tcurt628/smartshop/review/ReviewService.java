package com.tcurt628.smartshop.review;

import com.vmware.xenon.common.*;
import org.apache.commons.lang3.StringUtils;

import java.util.Collections;
import java.util.Map;

/**
 * Created by tcurtis on 2/24/16.
 */
public class ReviewService extends StatefulService {

   public static final Integer MIN_STAR = 1;
   public static final Integer MAX_STAR = 5;

   public static final String FACTORY_LINK = "/reviews";
   public static final String PRODUCT_FACTORY_LINK = "/products";

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
         // validateInitialState handles marking 'post' as complete or fail
         validateInitialState(post);
      } catch (Exception e) {
         logWarning("Error on handleStart for: %s", post);
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

   /** Validates initial state of a {@code Review}. Marks {@code post} as complete if successful. */
   private void validateInitialState(Operation post) {
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

      String target = PRODUCT_FACTORY_LINK + String.format("?$expand&filter=(documentSelfLink eq '%s')", state.productLink);
      Operation getProduct = Operation.createGet(this, target)
            .setReferer(this.getUri())
            .setCompletion(
                  (op, ex) -> {
                     if (ex != null) {
                        logSevere("Error during completion logic: [productLink=%s] [exception=%s]", state.productLink, ex);
                        throw new IllegalStateException("Error looking up productLink: " + state.productLink, ex);
                     }

                     ServiceDocumentQueryResult result = op.getBody(ServiceDocumentQueryResult.class);
                     logFine("Forwarding OData query returned result: %s", result);

                     Map<String, Object> matchedDocuments =
                           result == null || result.documents == null ? Collections.emptyMap() : result.documents;
                     logFine("result.documents: %s", matchedDocuments);

                     Object productMatch = matchedDocuments.get(state.productLink);
                     logFine("productMatch: %s", productMatch);
                     if (productMatch == null) {
                        String message = String.format("[productLink=%s] does not exist", state.productLink);
                        logWarning(message);
                        post.fail(new IllegalArgumentException(message));
                        return;
                     }

                     logFine("productLink found! Review is valid. Marking complete...");
                     post.complete();
                  }
            );
      this.getHost().forwardRequest(ReviewHost.PRODUCT_NODE_SELECTOR_URI, getProduct);
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
