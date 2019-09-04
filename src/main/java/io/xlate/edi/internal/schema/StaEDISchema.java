/*******************************************************************************
 * Copyright 2017 xlate.io LLC, http://www.xlate.io
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 ******************************************************************************/
package io.xlate.edi.internal.schema;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import io.xlate.edi.schema.EDIComplexType;
import io.xlate.edi.schema.EDIReference;
import io.xlate.edi.schema.EDISchemaException;
import io.xlate.edi.schema.EDISyntaxRule;
import io.xlate.edi.schema.EDIType;
import io.xlate.edi.schema.Schema;

class StaEDISchema implements Schema {

    static final String MAIN = "io.xlate.edi.internal.internal.schema.MAIN";

    final SchemaProxy proxy = new SchemaProxy(this);

    private Map<String, EDIType> types = Collections.emptyMap();
    private EDIComplexType mainLoop = null;

    @Override
    public EDIComplexType getMainLoop() {
        return mainLoop;
    }

    void setTypes(Map<String, EDIType> types) throws EDISchemaException {
        if (types == null) {
            throw new NullPointerException("types cannot be null");
        }

        this.types = Collections.unmodifiableMap(types);

        if (!types.containsKey(MAIN)) {
            throw new EDISchemaException("main loop not in schema");
        }

        this.mainLoop = (EDIComplexType) types.get(MAIN);
    }

    @Override
    public EDIType getType(String name) {
        return types.get(name);
    }

    @Override
    public boolean containsSegment(String name) {
        final EDIType type = types.get(name);
        return type != null && type.isType(EDIType.Type.SEGMENT);
    }

    @Override
    public Iterator<EDIType> iterator() {
        return types.values().iterator();
    }

    @Override
    public Schema reference(Schema referenced,
                            EDIComplexType parent,
                            EDIReference child) throws EDISchemaException {

        if (parent == null || !parent.isType(EDIType.Type.LOOP)) {
            throw new EDISchemaException("parent must be loop");
        }

        final String parentId = parent.getId();
        final EDIType knownParent = this.types.get(parentId);

        if (knownParent != parent) {
            throw new EDISchemaException("parent not in this schema");
        }

        EDIReference knownChild = null;
        List<EDIReference> references = parent.getReferences();

        for (EDIReference reference : references) {
            if (reference == child) {
                knownChild = reference;
                break;
            }
        }

        if (knownChild != child) {
            throw new EDISchemaException("child is not referenced by parent");
        }

        EDIComplexType main = referenced.getMainLoop();

        if (main == null || !main.isType(EDIType.Type.LOOP)) {
            throw new EDISchemaException("referenced schema root must be loop");
        }

        StaEDISchema merged = new StaEDISchema();
        merged.types = new HashMap<>(this.types);
        merged.mainLoop = merged.attach(main, this.mainLoop, parent, child);

        if (referenced instanceof StaEDISchema) {
            ((StaEDISchema) referenced).proxy.setSchema(merged);
        }

        //TODO: Consider :: should we overwrite?
        referenced.forEach(t -> merged.types.putIfAbsent(t.getId(), t));

        return merged;
    }

    private EDIComplexType attach(
                                  EDIComplexType referenced,
                                  EDIComplexType root,
                                  EDIComplexType parent,
                                  EDIReference child) {

        if (root != parent) {
            List<EDIReference> references = root.getReferences();

            for (int i = 0, m = references.size(); i < m; i++) {
                EDIReference reference = references.get(i);
                EDIType type = reference.getReferencedType();

                if (!type.isType(EDIType.Type.LOOP)) {
                    continue;
                }

                EDIComplexType loop = (EDIComplexType) type;
                EDIComplexType newParent;
                newParent = attach(referenced, loop, parent, child);

                if (newParent != null) {
                    return replaceReference(root, references, reference, i, newParent);
                }
            }
        } else {
            return addReference(referenced, root, parent, child);
        }

        return null;
    }

    private EDIComplexType replaceReference(
                                            EDIComplexType loop,
                                            List<EDIReference> references,
                                            EDIReference reference,
                                            int i,
                                            EDIComplexType newTarget) {

        references = new ArrayList<>(references);
        int minLoopOccurs = reference.getMinOccurs();
        int maxLoopOccurs = reference.getMaxOccurs();

        final Reference newReference;
        newReference = new Reference(newTarget, minLoopOccurs, maxLoopOccurs);
        references.set(i, newReference);

        final EDIComplexType newLoop;
        newLoop = new Structure(loop, references, getSyntaxRules(loop));
        types.put(newLoop.getId(), newLoop);
        return newLoop;
    }

    static List<EDISyntaxRule> getSyntaxRules(EDIComplexType type) {
        return type.getSyntaxRules();
    }

    static List<EDIReference> getReferences(EDIComplexType type) {
        return type.getReferences();
    }

    private EDIComplexType addReference(
                                        EDIComplexType referenced,
                                        EDIComplexType root,
                                        EDIComplexType parent,
                                        EDIReference child) {

        List<EDIReference> references = getReferences(root);
        int index = 0;

        for (EDIReference reference : references) {
            if (reference == child) {
                break;
            }
            index++;
        }

        EDIComplexType newParent;

        references = new ArrayList<>(references);

        for (EDIReference reference : referenced.getReferences()) {
            Reference newRef = new Reference(reference);
            newRef.setSchema(proxy);
            references.add(index++, newRef);
        }

        newParent = new Structure(parent, references, getSyntaxRules(parent));
        types.put(newParent.getId(), newParent);
        return newParent;
    }

    static class SchemaProxy implements Schema {
        private ThreadLocal<Schema> proxy = new ThreadLocal<>();
        private ThreadLocal<Map<String, EDIType>> controlTypes;

        public SchemaProxy(StaEDISchema schema) {
            proxy.set(schema);
            controlTypes = new ThreadLocal<>();
            controlTypes.set(Collections.emptyMap());
        }

        void setSchema(StaEDISchema schema) {
            proxy.set(schema);
        }

        void setControlTypes(Map<String, EDIType> controlTypes) {
            this.controlTypes.set(controlTypes);
        }

        @Override
        public Iterator<EDIType> iterator() {
            return proxy.get().iterator();
        }

        @Override
        public EDIComplexType getMainLoop() {
            return proxy.get().getMainLoop();
        }

        @Override
        public EDIType getType(String name) {
            EDIType type = proxy.get().getType(name);
            return type != null ? type : controlTypes.get().get(name);
        }

        @Override
        public boolean containsSegment(String name) {
            return proxy.get().containsSegment(name);
        }

        @Override
        public Schema reference(Schema referenced,
                                EDIComplexType parent,
                                EDIReference child) throws EDISchemaException {
            return proxy.get().reference(referenced, parent, child);
        }
    }
}