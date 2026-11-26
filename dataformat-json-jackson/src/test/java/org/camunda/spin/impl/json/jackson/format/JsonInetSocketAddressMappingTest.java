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
import static org.camunda.spin.Spin.JSON;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;

import org.junit.Before;
import org.junit.Test;

import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.type.TypeFactory;

/**
 * Verifies that the jackson-databind shipped with Spin does not perform a DNS
 * lookup when JSON is bound to {@link InetSocketAddress}, and that unresolved
 * IPv6 addresses keep their port unambiguous when mapped back to JSON.
 *
 * @see <a href="https://github.com/advisories/GHSA-hgj6-7826-r7m5">GHSA-hgj6-7826-r7m5</a>
 */
public class JsonInetSocketAddressMappingTest {

  protected ObjectMapper objectMapper;

  @Before
  public void setUp() {
    objectMapper = new JacksonJsonDataFormat("test").getObjectMapper();
  }

  @Test
  public void shouldNotResolveHostNameWhenReadingValue() throws Exception {
    InetSocketAddress address = objectMapper.readValue("\"localhost:9999\"", InetSocketAddress.class);

    assertThat(address.isUnresolved())
        .as("InetSocketAddress resolved localhost during deserialization: " + address)
        .isTrue();
    assertThat(address.getHostString()).isEqualTo("localhost");
    assertThat(address.getPort()).isEqualTo(9999);
  }

  @Test
  public void shouldNotResolveHostNameWhenMappingJsonToJava() {
    AddressHolder holder = JSON("{\"address\":\"localhost:9999\"}").mapTo(AddressHolder.class);

    InetSocketAddress address = holder.getAddress();
    assertThat(address.isUnresolved())
        .as("InetSocketAddress resolved localhost during deserialization: " + address)
        .isTrue();
    assertThat(address.getHostString()).isEqualTo("localhost");
    assertThat(address.getPort()).isEqualTo(9999);
  }

  @Test
  public void shouldNotResolveHostNamesWhenMappingJsonCollectionToJava() {
    JavaType listType =
        TypeFactory.defaultInstance().constructCollectionType(ArrayList.class, InetSocketAddress.class);

    List<InetSocketAddress> addresses =
        JSON("[\"localhost:8080\", \"127.0.0.1:9999\"]").mapTo(listType.toCanonical());

    assertThat(addresses).hasSize(2);
    for (InetSocketAddress address : addresses) {
      assertThat(address.isUnresolved())
          .as("InetSocketAddress resolved during deserialization: " + address)
          .isTrue();
    }
    assertThat(addresses.get(0).getHostString()).isEqualTo("localhost");
    assertThat(addresses.get(0).getPort()).isEqualTo(8080);
    assertThat(addresses.get(1).getHostString()).isEqualTo("127.0.0.1");
    assertThat(addresses.get(1).getPort()).isEqualTo(9999);
  }

  @Test
  public void shouldMapUnresolvedAddressJavaToJson() throws Exception {
    assertThat(objectMapper.writeValueAsString(InetSocketAddress.createUnresolved("127.0.0.1", 8080)))
        .isEqualTo("\"127.0.0.1:8080\"");
    assertThat(objectMapper.writeValueAsString(InetSocketAddress.createUnresolved("localhost", 6667)))
        .isEqualTo("\"localhost:6667\"");
  }

  @Test
  public void shouldBracketUnresolvedIpv6HostWhenMappingJavaToJson() throws Exception {
    assertThat(objectMapper.writeValueAsString(InetSocketAddress.createUnresolved("2001:db8::1", 443)))
        .isEqualTo("\"[2001:db8::1]:443\"");
    // host name stored with brackets must not be bracketed twice
    assertThat(objectMapper.writeValueAsString(InetSocketAddress.createUnresolved("[2001:db8::1]", 443)))
        .isEqualTo("\"[2001:db8::1]:443\"");

    AddressHolder holder = new AddressHolder();
    holder.setAddress(InetSocketAddress.createUnresolved("2001:db8::1", 443));
    assertThat(JSON(holder).prop("address").stringValue()).isEqualTo("[2001:db8::1]:443");
  }

  @Test
  public void shouldKeepIpv6PortWhenRoundTripping() {
    AddressHolder holder = JSON("{\"address\":\"[2001:db8::1]:443\"}").mapTo(AddressHolder.class);
    String json = JSON(holder).prop("address").stringValue();
    assertThat(json).isEqualTo("[2001:db8::1]:443");

    AddressHolder result = JSON(JSON(holder).toString()).mapTo(AddressHolder.class);
    assertThat(result.getAddress().getHostString()).isEqualTo(holder.getAddress().getHostString());
    assertThat(result.getAddress().getPort()).isEqualTo(443);

    // IPv6 literal without port must not have its last segment taken for a port
    holder = JSON("{\"address\":\"2001:db8::1\"}").mapTo(AddressHolder.class);
    assertThat(holder.getAddress().isUnresolved()).isTrue();
    assertThat(JSON(holder).prop("address").stringValue()).isEqualTo("[2001:db8::1]:0");
  }

  public static class AddressHolder {

    private InetSocketAddress address;

    public InetSocketAddress getAddress() {
      return address;
    }

    public void setAddress(InetSocketAddress address) {
      this.address = address;
    }
  }
}
