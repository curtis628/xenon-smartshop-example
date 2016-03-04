package com.tcurt628.smartshop.review;

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

import static com.tcurt628.smartshop.review.ReviewService.ReviewServiceState;
import static org.junit.Assert.assertEquals;

/**
 * Created by tcurtis on 2/24/16.
 */
public class ReviewServiceTest extends BasicReusableHostTestCase {

   private String productName = "Test Review Name";
   private String productDescription = "Description goes here...";
   private double productPrice = 5.00;

   @Before
   public void setUp() throws Throwable {
      this.host.setLoggingLevel(Level.FINE);
      this.host.startServiceAndWait(ReviewService.createFactory(), ReviewService.FACTORY_LINK, new ServiceDocument());
   }

   @Test
   public void testCRUD() throws Throwable {
   }
}
