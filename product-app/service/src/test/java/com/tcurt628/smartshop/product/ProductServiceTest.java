package com.tcurt628.smartshop.product;

import com.vmware.xenon.common.BasicReusableHostTestCase;
import com.vmware.xenon.common.Operation;
import com.vmware.xenon.common.ServiceDocument;
import com.vmware.xenon.common.UriUtils;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;

import java.net.URI;
import java.util.UUID;
import java.util.logging.Level;

import com.tcurt628.smartshop.product.model.Product;
import static org.junit.Assert.assertEquals;

/**
 * Created by tcurtis on 2/24/16.
 */
public class ProductServiceTest extends BasicReusableHostTestCase {

   private String productName = "Test Product Name";
   private String productDescription = "Description goes here...";
   private double productPrice = 5.00;

   @Before
   public void setUp() throws Throwable {
      this.host.setLoggingLevel(Level.FINE);
      this.host.startServiceAndWait(ProductService.createFactory(), ProductService.FACTORY_LINK, new ServiceDocument());
   }

   @Test
   @Ignore // need to create product node group and selector for this to work
   public void testCRUD() throws Throwable {
      // locate factory and create a service instance
      URI factoryUri = UriUtils.buildFactoryUri(this.host, ProductService.class);
      this.host.testStart(1);

      Product initialState = new Product();
      initialState.name = productName;
      initialState.description = productDescription;
      initialState.price = productPrice;

      Product[] responses = new Product[1];
      Operation post = Operation
            .createPost(factoryUri)
            .setBody(initialState).setCompletion((o, e) -> {
               if (e != null) {
                  this.host.failIteration(e);
                  return;
               }
               responses[0] = o.getBody(Product.class);
               this.host.completeIteration();
            });
      this.host.send(post);
      this.host.testWait();
      assertEquals(productName, responses[0].name);
      assertEquals(productDescription, responses[0].description);
      assertEquals(productPrice, responses[0].price, 0);
      URI childURI = UriUtils.buildUri(this.host, responses[0].documentSelfLink);

      // update
      this.host.testStart(1);
      String newDescription = "Updated description";
      double newPrice = 1.10;
      Product body = new Product();
      body.name = productName;
      body.price = newPrice;
      body.description = newDescription;
      Operation put = Operation
            .createPut(childURI)
            .setBody(body).setCompletion((o, e) -> {
               if (e != null) {
                  this.host.failIteration(e);
                  return;
               }
               responses[0] = o.getBody(Product.class);
               this.host.completeIteration();
            });
      this.host.send(put);
      this.host.testWait();
      assertEquals(productName, responses[0].name);
      assertEquals(newDescription, responses[0].description);
      assertEquals(newPrice, responses[0].price, 0);

      // delete instance
      System.out.println("Sending DELETE to: " + childURI);
      this.host.testStart(1);
      Operation delete = Operation
            .createDelete(childURI)
            .setCompletion((o, e) -> {
               if (e != null) {
                  this.host.failIteration(e);
                  return;
               }
               this.host.completeIteration();
            });
      this.host.send(delete);
      this.host.testWait();
   }
}