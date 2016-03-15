package com.tcurt628.smartshop.product;

import com.tcurt628.smartshop.product.model.Product;
import com.vmware.xenon.common.FactoryService;
import com.vmware.xenon.common.Operation;
import com.vmware.xenon.common.Service;
import com.vmware.xenon.common.StatefulService;
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
      return FactoryService.create(ProductService.class, Product.class);
   }

   public ProductService() {
      super(Product.class);
      super.toggleOption(ServiceOption.PERSISTENCE, true);
      super.toggleOption(ServiceOption.REPLICATION, true);
      super.setPeerNodeSelectorPath(ProductHost.PRODUCT_NODE_SELECTOR_URI);
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

      Product state = post.getBody(Product.class);
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
      Product currentState = getState(put);
      validateState(put);

      // replace current state, with the body of the request, in one step
      setState(put, currentState);

      Product body = put.getBody(Product.class);
      put.setBody(body);
      put.complete();
   }
}
