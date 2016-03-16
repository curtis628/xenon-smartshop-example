package com.tcurt628.smartshop.product;

import com.tcurt628.smartshop.product.model.Product;
import com.vmware.xenon.common.FactoryService;
import com.vmware.xenon.common.Operation;
import com.vmware.xenon.common.Service;
import com.vmware.xenon.common.StatefulService;
import org.apache.commons.lang3.StringUtils;

import java.util.logging.Level;
import java.util.logging.Logger;

public class ProductService extends StatefulService {

   public static final String FACTORY_LINK = "/products";

   /** Not crazy about this, but it works for now I guess... */
   private static Logger logger = Logger.getLogger(ProductService.class.getName());
   static {
      logger.setLevel(Level.FINE);
   }

   /** Create a default factory service that starts instances of this service on POST. */
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
         logFine("handleStart() completed successfully. [post=%s]", post);
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
      logFine("Validating product: %s", state);
      if (StringUtils.isEmpty(state.name)) {
         throw new IllegalArgumentException("Name cannot be empty");
      }

      if (state.price < 0) {
         throw new IllegalArgumentException("Price cannot be negative");
      }

      logInfo("Product valid: %s", state);
   }

   @Override
   public void handlePut(Operation put) {
      Product currentState = getState(put);
      validateState(put);

      // replace current state, with the body of the request, in one step
      setState(put, currentState);

      Product body = put.getBody(Product.class);
      put.setBody(body);
      put.complete();
      logFine("handlePut() completed successfully. [put=%s]", put);
   }
}
