/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.camel.kamelets.utils.format.converter.aws2.ddb;

import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.camel.CamelExecutionException;
import org.apache.camel.Exchange;
import org.apache.camel.component.aws2.ddb.Ddb2Constants;
import org.apache.camel.component.aws2.ddb.Ddb2Operations;
import org.apache.camel.component.jackson.JacksonDataFormat;
import org.apache.camel.kamelets.utils.format.spi.DataTypeConverter;
import org.apache.camel.kamelets.utils.format.spi.annotations.DataType;
import software.amazon.awssdk.services.dynamodb.model.AttributeAction;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.AttributeValueUpdate;
import software.amazon.awssdk.services.dynamodb.model.ReturnValue;

/**
 * Maps Json body to DynamoDB attribute value map and sets the attribute map as Camel DynamoDB header entries.
 *
 * Json property names map to attribute keys and Json property values map to attribute values.
 *
 * During mapping the Json property types resolve to the respective attribute types
 * ({@code String, StringSet, Boolean, Number, NumberSet, Map, Null}). Primitive typed arrays in Json get mapped to
 * {@code StringSet} or {@code NumberSet} attribute values.
 *
 * The input type supports the operations: PutItem, UpdateItem, DeleteItem
 *
 * For PutItem operation the Json body defines all item attributes.
 *
 * For DeleteItem operation the Json body defines only the primary key attributes that identify the item to delete.
 *
 * For UpdateItem operation the Json body defines both key attributes to identify the item to be updated and all item
 * attributes tht get updated on the item.
 *
 * The given Json body can use "operation", "key" and "item" as top level properties. Both define a Json object that
 * will be mapped to respective attribute value maps:
 *
 * <pre>
 * {@code
 * {
 *   "operation": "PutItem"
 *   "key": {},
 *   "item": {}
 * }
 * }
 * </pre>
 *
 * The converter will extract the objects and set respective attribute value maps as header entries. This is a
 * comfortable way to define different key and item attribute value maps e.g. on UpdateItem operation.
 *
 * In case key and item attribute value maps are identical you can omit the special top level properties completely. The
 * converter will map the whole Json body as is then and use it as source for the attribute value map.
 */
@DataType(scheme = "aws2-ddb", name = "json", mediaType = "application/json")
public class Ddb2JsonInputType implements DataTypeConverter {

    private final JacksonDataFormat dataFormat = new JacksonDataFormat(new ObjectMapper(), JsonNode.class);

    @Override
    public void convert(Exchange exchange) {
        if (exchange.getMessage().getHeaders().containsKey(Ddb2Constants.ITEM) ||
                exchange.getMessage().getHeaders().containsKey(Ddb2Constants.KEY)) {
            return;
        }

        JsonNode jsonBody = getBodyAsJsonNode(exchange);

        String operation
                = Optional.ofNullable(jsonBody.get("operation")).map(JsonNode::asText).orElse(Ddb2Operations.PutItem.name());
        if (exchange.hasProperties() && exchange.getProperty("operation", String.class) != null) {
            operation = exchange.getProperty("operation", String.class);
        }

        if (exchange.getIn().getHeaders().containsKey(Ddb2Constants.OPERATION)) {
            operation = exchange.getIn().getHeader(Ddb2Constants.OPERATION, Ddb2Operations.class).name();
        }

        JsonNode key = jsonBody.get("key");
        JsonNode item = jsonBody.get("item");

        Map<String, Object> keyProps;
        if (key != null) {
            keyProps = dataFormat.getObjectMapper().convertValue(key, new TypeReference<Map<String, Object>>() {
            });
        } else {
            keyProps = dataFormat.getObjectMapper().convertValue(jsonBody, new TypeReference<Map<String, Object>>() {
            });
        }

        Map<String, Object> itemProps;
        if (item != null) {
            itemProps = dataFormat.getObjectMapper().convertValue(item, new TypeReference<Map<String, Object>>() {
            });
        } else {
            itemProps = keyProps;
        }

        final Map<String, AttributeValue> keyMap = getAttributeValueMap(keyProps);

        switch (Ddb2Operations.valueOf(operation)) {
            case PutItem:
                exchange.getMessage().setHeader(Ddb2Constants.OPERATION, Ddb2Operations.PutItem);
                exchange.getMessage().setHeader(Ddb2Constants.ITEM, getAttributeValueMap(itemProps));
                setHeaderIfNotPresent(Ddb2Constants.RETURN_VALUES, ReturnValue.ALL_OLD.toString(), exchange);
                break;
            case UpdateItem:
                exchange.getMessage().setHeader(Ddb2Constants.OPERATION, Ddb2Operations.UpdateItem);
                exchange.getMessage().setHeader(Ddb2Constants.KEY, keyMap);
                exchange.getMessage().setHeader(Ddb2Constants.UPDATE_VALUES, getAttributeValueUpdateMap(itemProps));
                setHeaderIfNotPresent(Ddb2Constants.RETURN_VALUES, ReturnValue.ALL_NEW.toString(), exchange);
                break;
            case DeleteItem:
                exchange.getMessage().setHeader(Ddb2Constants.OPERATION, Ddb2Operations.DeleteItem);
                exchange.getMessage().setHeader(Ddb2Constants.KEY, keyMap);
                setHeaderIfNotPresent(Ddb2Constants.RETURN_VALUES, ReturnValue.ALL_OLD.toString(), exchange);
                break;
            default:
                throw new UnsupportedOperationException(String.format("Unsupported operation '%s'", operation));
        }
    }

    private JsonNode getBodyAsJsonNode(Exchange exchange) {
        try {
            if (exchange.getMessage().getBody() instanceof JsonNode) {
                return exchange.getMessage().getMandatoryBody(JsonNode.class);
            }

            return (JsonNode) dataFormat.unmarshal(exchange, exchange.getMessage().getMandatoryBody(InputStream.class));
        } catch (Exception e) {
            throw new CamelExecutionException("Failed to get mandatory Json node from message body", exchange, e);
        }
    }

    private void setHeaderIfNotPresent(String headerName, Object value, Exchange exchange) {
        exchange.getMessage().setHeader(headerName, value);
    }

    private Map<String, AttributeValue> getAttributeValueMap(Map<String, Object> body) {
        final Map<String, AttributeValue> attributeValueMap = new LinkedHashMap<>();

        for (Map.Entry<String, Object> attribute : body.entrySet()) {
            attributeValueMap.put(attribute.getKey(), getAttributeValue(attribute.getValue()));
        }

        return attributeValueMap;
    }

    private Map<String, AttributeValueUpdate> getAttributeValueUpdateMap(Map<String, Object> body) {
        final Map<String, AttributeValueUpdate> attributeValueMap = new LinkedHashMap<>();

        for (Map.Entry<String, Object> attribute : body.entrySet()) {
            attributeValueMap.put(attribute.getKey(), getAttributeValueUpdate(attribute.getValue()));
        }

        return attributeValueMap;
    }

    private static AttributeValue getAttributeValue(Object value) {
        if (value == null) {
            return AttributeValue.builder().nul(true).build();
        }

        if (value instanceof String) {
            return AttributeValue.builder().s(value.toString()).build();
        }

        if (value instanceof Integer) {
            return AttributeValue.builder().n(value.toString()).build();
        }

        if (value instanceof Boolean) {
            return AttributeValue.builder().bool((Boolean) value).build();
        }

        if (value instanceof String[]) {
            return AttributeValue.builder().ss((String[]) value).build();
        }

        if (value instanceof int[]) {
            return AttributeValue.builder().ns(Stream.of((int[]) value).map(Object::toString).collect(Collectors.toList()))
                    .build();
        }

        if (value instanceof List) {
            List<?> values = (List<?>) value;

            if (values.isEmpty()) {
                return AttributeValue.builder().ss().build();
            } else if (values.get(0) instanceof Integer) {
                return AttributeValue.builder().ns(values.stream().map(Object::toString).collect(Collectors.toList())).build();
            } else {
                return AttributeValue.builder().ss(values.stream().map(Object::toString).collect(Collectors.toList())).build();
            }
        }

        if (value instanceof Map) {
            Map<String, AttributeValue> nestedAttributes = new LinkedHashMap<>();

            for (Map.Entry<?, ?> nested : ((Map<?, ?>) value).entrySet()) {
                nestedAttributes.put(nested.getKey().toString(), getAttributeValue(nested.getValue()));
            }

            return AttributeValue.builder().m(nestedAttributes).build();
        }

        return AttributeValue.builder().s(value.toString()).build();
    }

    private static AttributeValueUpdate getAttributeValueUpdate(Object value) {
        return AttributeValueUpdate.builder()
                .action(AttributeAction.PUT)
                .value(getAttributeValue(value)).build();
    }
}
