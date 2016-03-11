package com.tcurt628.smartshop.review.model;

import com.vmware.xenon.common.ServiceDocument;

/**
 * Created by tcurtis on 3/8/16.
 */
public class Review extends ServiceDocument {
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
