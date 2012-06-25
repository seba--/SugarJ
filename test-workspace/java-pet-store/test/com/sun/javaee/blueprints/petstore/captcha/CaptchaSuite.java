/*
 * CaptchaSuite.java
 * JUnit based test
 *
 * Created on September 19, 2006, 2:59 PM
 */

package com.sun.javaee.blueprints.petstore.captcha;

import junit.framework.*;

/**
 *
 * @author inder
 */
public class CaptchaSuite extends TestCase {
    
    public CaptchaSuite(String testName) {
        super(testName);
    }

    protected void setUp() throws Exception {
    }

    protected void tearDown() throws Exception {
    }

    /**
     * suite method automatically generated by JUnit module
     */
    public static Test suite() {
        TestSuite suite = new TestSuite("CaptchaSuite");
        suite.addTest(com.sun.javaee.blueprints.petstore.captcha.RandomStringTest.suite());
        suite.addTest(com.sun.javaee.blueprints.petstore.captcha.SimpleCaptchaTest.suite());
        suite.addTest(com.sun.javaee.blueprints.petstore.captcha.BlueFilterTest.suite());
        return suite;
    }
    
}
