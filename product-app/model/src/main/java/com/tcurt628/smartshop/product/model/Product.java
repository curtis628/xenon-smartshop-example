package com.tcurt628.smartshop.product.model;

import com.vmware.xenon.common.ServiceDocument;

/**
 * Created by tcurtis on 3/8/16.
 */
public class Product extends ServiceDocument {
   public String name;
   public String description;
   public double price;

   @Override
   public String toString() {
      // TODO figure out format string for two decimal places for float
      return String.format("Product: [name=%s] [description=%s] [price=%2f]",
            name, description, price);
   }
}
