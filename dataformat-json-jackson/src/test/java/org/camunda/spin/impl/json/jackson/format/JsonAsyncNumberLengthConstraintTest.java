/*
 * Copyright Camunda Services GmbH and/or licensed to Camunda Services GmbH
 * under one or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information regarding copyright
 * ownership. Camunda licenses this file to you under the Apache License,
 * Version 2.0; you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.camunda.spin.impl.json.jackson.format;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.Assert.fail;

import java.nio.charset.StandardCharsets;

import org.junit.Before;
import org.junit.Test;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.core.async.ByteArrayFeeder;
import com.fasterxml.jackson.core.exc.StreamConstraintsException;

/**
 * Verifies that the jackson-core non-blocking (async) parser shipped with Spin
 * enforces {@code StreamReadConstraints.maxNumberLength}, both for numbers that
 * arrive in a single chunk and for digits streamed across many chunks.
 *
 * @see <a href="https://github.com/advisories/GHSA-72hv-8253-57qq">GHSA-72hv-8253-57qq</a>
 */
public class JsonAsyncNumberLengthConstraintTest {

  // bounded so a vulnerable parser cannot exhaust memory during the test
  protected static final int MAX_CHUNKS = 32;
  protected static final int SMALL_CHUNK = 100;

  protected JsonFactory jsonFactory;
  protected int maxNumberLength;

  @Before
  public void setUp() {
    jsonFactory = new JacksonJsonDataFormat("test").getObjectMapper().getFactory();
    maxNumberLength = jsonFactory.streamReadConstraints().getMaxNumberLength();
  }

  @Test
  public void shouldRejectTooLongIntegerInSingleChunk() throws Exception {
    byte[] payload = utf8("{\"v\":" + digits(maxNumberLength * 2) + "}");

    assertTooLongNumberRejected(payload, JsonToken.VALUE_NUMBER_INT);
  }

  @Test
  public void shouldRejectTooLongNegativeIntegerInSingleChunk() throws Exception {
    byte[] payload = utf8("{\"v\":-" + digits(maxNumberLength * 2) + "}");

    assertTooLongNumberRejected(payload, JsonToken.VALUE_NUMBER_INT);
  }

  @Test
  public void shouldRejectTooLongFractionInSingleChunk() throws Exception {
    byte[] payload = utf8("{\"v\":0." + digits(maxNumberLength * 2) + "}");

    assertTooLongNumberRejected(payload, JsonToken.VALUE_NUMBER_FLOAT);
  }

  @Test
  public void shouldRejectTooLongExponentInSingleChunk() throws Exception {
    byte[] payload = utf8("{\"v\":1.1E" + digits(maxNumberLength * 2));

    assertTooLongNumberRejected(payload, JsonToken.VALUE_NUMBER_FLOAT);
  }

  @Test
  public void shouldRejectIntegerDigitsStreamedAcrossChunks() throws Exception {
    assertStreamedDigitsRejected("{\"v\":");
  }

  @Test
  public void shouldRejectNegativeIntegerDigitsStreamedAcrossChunks() throws Exception {
    assertStreamedDigitsRejected("{\"v\":-");
  }

  @Test
  public void shouldRejectFractionDigitsStreamedAcrossChunks() throws Exception {
    assertStreamedDigitsRejected("{\"v\":0.");
  }

  @Test
  public void shouldRejectExponentDigitsStreamedAcrossChunks() throws Exception {
    assertStreamedDigitsRejected("{\"v\":1.1E");
  }

  @Test
  public void shouldParseIntegerJustUnderLimitStreamedAcrossChunks() throws Exception {
    String number = digits(maxNumberLength - 1);

    try (JsonParser parser = jsonFactory.createNonBlockingByteArrayParser()) {
      ByteArrayFeeder feeder = (ByteArrayFeeder) parser;
      drainPreamble(parser, feeder, "{\"v\":");

      byte[] digits = utf8(number);
      for (int offset = 0; offset < digits.length; offset += SMALL_CHUNK) {
        int end = Math.min(offset + SMALL_CHUNK, digits.length);
        feeder.feedInput(digits, offset, end);
        assertThat(parser.nextToken()).isEqualTo(JsonToken.NOT_AVAILABLE);
      }

      byte[] tail = utf8("}");
      feeder.feedInput(tail, 0, tail.length);
      feeder.endOfInput();

      assertThat(parser.nextToken()).isEqualTo(JsonToken.VALUE_NUMBER_INT);
      assertThat(parser.getText()).isEqualTo(number);
      assertThat(parser.nextToken()).isEqualTo(JsonToken.END_OBJECT);
    }
  }

  protected void assertTooLongNumberRejected(byte[] payload, JsonToken numberToken) throws Exception {
    try (JsonParser parser = jsonFactory.createNonBlockingByteArrayParser()) {
      ByteArrayFeeder feeder = (ByteArrayFeeder) parser;
      feeder.feedInput(payload, 0, payload.length);
      feeder.endOfInput();

      boolean numberFound = false;
      try {
        while (parser.nextToken() != null) {
          if (parser.currentToken() == numberToken) {
            numberFound = true;
            parser.getText();
          }
        }
        fail("Async parser must reject a number longer than " + maxNumberLength
            + " characters (number found? " + numberFound + ")");
      } catch (StreamConstraintsException e) {
        assertThat(e.getMessage()).contains("Number value length");
      }
    }
  }

  protected void assertStreamedDigitsRejected(String preamble) throws Exception {
    try (JsonParser parser = jsonFactory.createNonBlockingByteArrayParser()) {
      ByteArrayFeeder feeder = (ByteArrayFeeder) parser;
      drainPreamble(parser, feeder, preamble);

      byte[] digits = utf8(digits(SMALL_CHUNK));
      int expectedFailChunk = (maxNumberLength / SMALL_CHUNK) + 1;
      int chunksFed = 0;
      try {
        for (int i = 0; i < MAX_CHUNKS; i++) {
          feeder.feedInput(digits, 0, digits.length);
          chunksFed++;
          assertThat(parser.nextToken()).isEqualTo(JsonToken.NOT_AVAILABLE);
        }
        fail("Async parser accepted " + (SMALL_CHUNK * MAX_CHUNKS)
            + " streamed digits with maxNumberLength=" + maxNumberLength);
      } catch (StreamConstraintsException e) {
        assertThat(e.getMessage()).contains("Number value length");
        assertThat(chunksFed).isGreaterThan(1);
        assertThat(chunksFed).isLessThanOrEqualTo(expectedFailChunk + 1);
      }
    }
  }

  protected void drainPreamble(JsonParser parser, ByteArrayFeeder feeder, String preamble) throws Exception {
    byte[] bytes = utf8(preamble);
    feeder.feedInput(bytes, 0, bytes.length);

    JsonToken token;
    while ((token = parser.nextToken()) != JsonToken.NOT_AVAILABLE) {
      if (token == null) {
        fail("Parser ended unexpectedly while draining preamble");
      }
    }
  }

  protected static String digits(int count) {
    StringBuilder sb = new StringBuilder(count);
    for (int i = 0; i < count; i++) {
      sb.append((char) ('1' + (i % 9)));
    }
    return sb.toString();
  }

  protected static byte[] utf8(String value) {
    return value.getBytes(StandardCharsets.UTF_8);
  }
}
