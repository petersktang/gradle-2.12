/*
 * Copyright 2013 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.gradle.internal.serialize

import org.gradle.internal.serialize.kryo.KryoBackedDecoder
import org.gradle.internal.serialize.kryo.KryoBackedEncoder
import org.gradle.messaging.remote.internal.Message
import spock.lang.Specification

abstract class SerializerSpec extends Specification {
    public <T> T serialize(T value, Serializer<T> serializer) {
        def bytes = toBytes(value, serializer)
        return fromBytes(bytes, serializer)
    }

    /**
     * Serializes and deserializes the given value, asserting that the generated byte sequence is shorter than it would be when default
     * Java serialization is used.
     */
    public <T> T usesEfficientSerialization(T value, Serializer<T> serializer, Integer expectedLength = null) {
        def bytes = toBytes(value, serializer)
        def length = bytes.length

        def defaultBytes = new ByteArrayOutputStream()
        Message.send(value, defaultBytes)
        def defaultLength = defaultBytes.size()

        println "${length} < ${defaultLength}"
        assert length < defaultLength
        assert expectedLength == null || length == expectedLength
        return fromBytes(bytes, serializer)
    }

    public <T> T fromBytes(byte[] bytes, Serializer<T> serializer) {
        return serializer.read(new KryoBackedDecoder(new ByteArrayInputStream(bytes)))
    }

    public <T> byte[] toBytes(T value, Serializer<T> serializer) {
        def bytes = new ByteArrayOutputStream()
        def encoder = new KryoBackedEncoder(bytes)
        serializer.write(encoder, value)
        encoder.flush()

        return bytes.toByteArray()
    }
}