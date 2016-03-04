package com.tcurt628.smartshop.product;

import com.vmware.xenon.common.*;
import org.apache.commons.lang3.StringUtils;

/**
 * Created by tcurtis on 2/24/16.
 */
public class ProductService extends StatefulService {

   public static final String FACTORY_LINK = "/products";

   /**
    * Create a default factory service that starts instances of this service on POST.
    */
   public static Service createFactory() {
      return FactoryService.create(ProductService.class, ProductServiceState.class);
   }

   public ProductService() {
      super(ProductServiceState.class);
      super.toggleOption(ServiceOption.PERSISTENCE, true);
      super.toggleOption(ServiceOption.REPLICATION, true);
      super.setPeerNodeSelectorPath(ProductHost.PRODUCT_NODE_SELECTOR_URI);
   }

   public static class ProductServiceState extends ServiceDocument {
      public String name;
      public String description;
      public double price;
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

   private void validateState(Operation post) {
      if (!post.hasBody()) {
         throw new IllegalArgumentException("Must include non-empty body");
      }

      ProductServiceState state = post.getBody(ProductServiceState.class);
      if (StringUtils.isEmpty(state.name)) {
         throw new IllegalArgumentException("Name cannot be empty");
      }

      if (state.price < 0) {
         throw new IllegalArgumentException("Price cannot be negative");
      }
   }

   @Override
   public void handlePut(Operation put) {
      logFine("handlePut(). [put=%s]", put);
      ProductServiceState currentState = getState(put);
      validateState(put);

      // replace current state, with the body of the request, in one step
      setState(put, currentState);

      ProductServiceState body = put.getBody(ProductServiceState.class);
      put.setBody(body);
      put.complete();
   }
}
