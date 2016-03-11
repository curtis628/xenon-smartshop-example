package com.tcurt628.smartshop.review;

import com.tcurt628.smartshop.product.model.Product;
import com.tcurt628.smartshop.review.model.Review;
import com.tcurt628.smartshop.review.model.ReviewUpdateRequest;
import com.vmware.xenon.common.*;
import com.vmware.xenon.dns.services.DNSService;
import com.vmware.xenon.services.common.ServiceUriPaths;
import org.apache.commons.lang3.StringUtils;

import java.net.URI;
import java.util.Collections;
import java.util.Map;

/**
 * Created by tcurtis on 2/24/16.
 */
public class ReviewService extends StatefulService {

   public static final Integer MIN_STAR = 1;
   public static final Integer MAX_STAR = 5;

   public static final String FACTORY_LINK = "/reviews";
   public static final String PRODUCT_SERVICE_NAME = "ProductService";
   public static final String PRODUCT_FACTORY_LINK = "/products";

   /**
    * Create a default factory service that starts instances of this service on POST.
    */
   public static Service createFactory() {
      return FactoryService.create(ReviewService.class, Review.class);
   }

   public ReviewService() {
      super(Review.class);
      super.toggleOption(ServiceOption.PERSISTENCE, true);
      super.toggleOption(ServiceOption.REPLICATION, true);
      super.setPeerNodeSelectorPath(ReviewHost.REVIEW_NODE_SELECTOR_URI);
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

   @Override
   public void handlePut(Operation put) {
      throw new UnsupportedOperationException(
            "PUT not supported; Please use PATCH (with ReviewUpdateRequest) instead");
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

      Review state = post.getBody(Review.class);
      logWarning("Processing state: %s", state);

      validateStars(state.stars);
      validateContent(state.content);

      // Ensure that the product exists
      if (StringUtils.isEmpty(state.productLink)) {
         throw new IllegalArgumentException("productLink cannot be empty");
      }

      // Here are two ways of discovering the product service from review service

      // 1st Way - is to query the node selector

      String target = PRODUCT_FACTORY_LINK + String.format("?expand&$filter=(documentSelfLink eq '%s')", state.productLink);
      Operation getProduct = Operation.createGet(this, target)
            .setReferer(this.getUri())
            .setCompletion(
                  (op, ex) -> {
                     if (ex != null) {
                        logSevere("Error during node selector logic: [productLink=%s] [exception=%s]", state.productLink, ex);
                        throw new IllegalStateException("Error looking up productLink: " + state.productLink, ex);
                     }

                     ServiceDocumentQueryResult result = op.getBody(ServiceDocumentQueryResult.class);
                     Long count = result != null ? result.documentCount : 0;
                     logInfo("Forwarding OData query returned %d results", count);

                     Map<String, Object> matchedDocuments =
                           result == null || result.documents == null ? Collections.emptyMap() : result.documents;
                     logInfo("result.documents: %s", matchedDocuments);

                     Object productMatch = matchedDocuments.get(state.productLink);
                     logInfo("productMatch: %s", productMatch);
                     if (productMatch == null) {
                        String message = String.format("[productLink=%s] does not exist", state.productLink);
                        logWarning(message);
                        // Ideally we want to fail here, but since we want to show the second way of querying, we will do it later
//                        post.fail(new IllegalArgumentException(message));
                        return;
                     }

                     logInfo("productLink found using node selector! Review is valid. Marking complete...");
                     // Ideally we want to complete here, but since we want to show the second way of querying, we will do it later
                     Product product = Utils.fromJson(productMatch, Product.class);
                     logWarning("Product found! [product=%s]", product);
//                   post.complete();
                  }
            );
      this.getHost().forwardRequest(ReviewHost.PRODUCT_NODE_SELECTOR_URI, getProduct);


      // 2nd Way - is to query the DNS, get the host where product is running and issue a GET on that directly

      String productServiceDNSLookupQuery = String.format("$filter=serviceName eq %s", PRODUCT_SERVICE_NAME);
      URI productServiceDNSLookupURI = UriUtils.buildUri(ReviewHost.hostArguments.dnshost, ReviewHost.hostArguments.dnsport, ServiceUriPaths.DNS + "/query", productServiceDNSLookupQuery);

      Operation.CompletionHandler productLookupHandler = (op, ex) -> {
         if(ex != null) {
            String message = String.format("[productLink=%s] does not exist", state.productLink);
            logSevere(message);
            post.fail(new IllegalArgumentException(message));
            return;
         }

         logInfo("productLink found using DNS lookup! Review is valid. Marking complete...");
         post.complete();
      };

      Operation.CompletionHandler productServiceDNSLookupQueryHandler = (o, e) -> {
         if(e != null) {
            logSevere("Error during product service DNS lookup: [ProductLink=%s], [Exception=%s]", state.productLink, e);
            throw new IllegalStateException("Error looking up DNS for product service: " + state.productLink, e);
         }
         ServiceDocumentQueryResult result = o.getBody(ServiceDocumentQueryResult.class);

         if(result.documentLinks == null || result.documentLinks.size() != 1) {
            logSevere("Error during product service DNS lookup: DocumentLinks is wrong [ProductLink=%s], [Exception=%s]", state.productLink, e);
            throw new IllegalStateException("Error looking up DNS for product service: " + state.productLink, e);
         }

         DNSService.DNSServiceState serviceState =
               Utils.fromJson((String) result.documents.get(result.documentLinks.get(0)),
                     DNSService.DNSServiceState.class);

         if(serviceState.nodeReferences.size() > 0) {
            URI productURI = UriUtils.extendUri(serviceState.nodeReferences.iterator().next(), state.productLink);
            logInfo("Product URI = [%s]", productURI);

            Operation getProductDirect = Operation.createGet(productURI)
                  .addPragmaDirective(Operation.PRAGMA_DIRECTIVE_NO_QUEUING)
                  .setCompletion(productLookupHandler);
            getProductDirect.setReferer(this.getUri());
            this.getHost().sendRequest(getProductDirect);
         } else {
            logSevere("Error during product service DNS lookup: No Nodes found [ProductLink=%s], [Exception=%s]", state.productLink, e);
            throw new IllegalStateException("Error looking up DNS for product service: " + state.productLink, e);
         }
      };

      Operation getProductNodeFromDNS = Operation.createGet(productServiceDNSLookupURI)
            .setCompletion(productServiceDNSLookupQueryHandler)
            .setReferer(this.getUri());

      this.getHost().sendRequest(getProductNodeFromDNS);
   }

   @Override
   public void handlePatch(Operation patch) {
      logInfo("handlePatch(). [patch=%s]", patch);
      Review currentState = getState(patch);
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
