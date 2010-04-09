package org.esa.beam.synergy.operators;

import junit.framework.Test;
import junit.framework.TestCase;
import junit.framework.TestSuite;

/**
 * Test class for Aerosol retrieval over land within Synergy project.
 *
 * @author Olaf Danne
 * @version $Revision: 5439 $ $Date: 2009-06-04 18:04:38 +0200 (Do, 04 Jun 2009) $
 */

/**
 * Unit test for simple App.
 */
public class MasterOpTest
    extends TestCase
{
    /**
     * Create the test case
     *
     * @param testName name of the test case
     */
    public MasterOpTest( String testName )
    {
        super( testName );
    }

    /**
     * @return the suite of tests being tested
     */
    public static Test suite()
    {
        return new TestSuite( MasterOpTest.class );
    }

    public void testIsAuxdataAvailable() {
        assertTrue(MasterOp.validateAuxdata());
    }
}
