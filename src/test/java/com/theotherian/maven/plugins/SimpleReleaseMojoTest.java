package com.theotherian.maven.plugins;


import org.junit.Assert;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class SimpleReleaseMojoTest {

  @Test
  public void getNonversionedCoordinates() throws Exception {
    String actual = new SimpleReleaseMojo().getNonversionedCoordinates("com.theotherian.example:example:jar:1.0");
    assertEquals("com.theotherian.example:example", actual);
  }
}
