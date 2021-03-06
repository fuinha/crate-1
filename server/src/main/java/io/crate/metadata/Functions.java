/*
 * Licensed to Crate under one or more contributor license agreements.
 * See the NOTICE file distributed with this work for additional
 * information regarding copyright ownership.  Crate licenses this file
 * to you under the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.  You may
 * obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or
 * implied.  See the License for the specific language governing
 * permissions and limitations under the License.
 *
 * However, if you have executed another commercial license agreement
 * with Crate these terms will supersede the license and you may use the
 * software solely pursuant to the terms of the relevant commercial
 * agreement.
 */

package io.crate.metadata;

import io.crate.common.annotations.VisibleForTesting;
import io.crate.common.collections.Lists2;
import io.crate.expression.symbol.Symbol;
import io.crate.expression.symbol.Symbols;
import io.crate.expression.symbol.format.Style;
import io.crate.metadata.functions.BoundVariables;
import io.crate.metadata.functions.Signature;
import io.crate.metadata.functions.SignatureBinder;
import io.crate.metadata.pgcatalog.OidHash;
import io.crate.types.DataType;
import io.crate.types.DataTypes;
import io.crate.types.TypeSignature;
import org.apache.logging.log4j.Logger;
import org.elasticsearch.common.inject.Inject;
import org.elasticsearch.common.logging.Loggers;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiFunction;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import static io.crate.common.collections.Lists2.getOnlyElement;

public class Functions {

    private static final Logger LOGGER = Loggers.getLogger(Functions.class);

    private final Map<FunctionName, List<FunctionProvider>> udfFunctionImplementations = new ConcurrentHashMap<>();
    private final Map<FunctionName, List<FunctionProvider>> functionImplementations;

    @Inject
    public Functions(Map<FunctionName, List<FunctionProvider>> functionImplementationsBySignature) {
        this.functionImplementations = functionImplementationsBySignature;
    }

    public Map<FunctionName, List<FunctionProvider>> functionResolvers() {
        return functionImplementations;
    }

    public Map<FunctionName, List<FunctionProvider>> udfFunctionResolvers() {
        return udfFunctionImplementations;
    }

    public void registerUdfFunctionImplementationsForSchema(
        String schema, Map<FunctionName, List<FunctionProvider>> functions) {
        // remove deleted ones before re-registering all current ones for the given schema
        udfFunctionImplementations.entrySet()
            .removeIf(
                function ->
                    schema.equals(function.getKey().schema())
                    && functions.get(function.getKey()) == null);
        udfFunctionImplementations.putAll(functions);
    }

    public void deregisterUdfResolversForSchema(String schema) {
        udfFunctionImplementations.keySet()
            .removeIf(function -> schema.equals(function.schema()));
    }

    @Nullable
    private static Signature findSignatureByOid(Map<FunctionName, List<FunctionProvider>> functions, int oid) {
        for (Map.Entry<FunctionName, List<FunctionProvider>> func : functions.entrySet()) {
            for (FunctionProvider sig : func.getValue()) {
                if (Objects.equals(oid, OidHash.functionOid(sig.getSignature()))) {
                    return sig.getSignature();
                }
            }
        }
        return null;
    }

    @Nullable
    public Signature findFunctionSignatureByOid(int oid) {
        Signature sig = findSignatureByOid(udfFunctionImplementations, oid);
        return sig != null ? sig : findSignatureByOid(functionImplementations, oid);
    }

    /**
     * Return a function that matches the name/arguments.
     *
     * <pre>
     * {@code
     * Lookup logic:
     *     No schema:   Built-ins -> Function or UDFs in searchPath
     *     With Schema: Function or UDFs in schema
     * }
     * </pre>
     *
     * @throws UnsupportedOperationException if the function wasn't found
     */
    public FunctionImplementation get(@Nullable String suppliedSchema,
                                      String functionName,
                                      List<Symbol> arguments,
                                      SearchPath searchPath) {
        FunctionName fqnName = new FunctionName(suppliedSchema, functionName);
        FunctionImplementation func = resolveFunctionBySignature(
            fqnName,
            arguments,
            searchPath,
            functionImplementations
        );
        if (func == null) {
            func = resolveFunctionBySignature(
                fqnName,
                arguments,
                searchPath,
                udfFunctionImplementations
            );
        }
        if (func == null) {
            raiseUnknownFunction(suppliedSchema, functionName, arguments, List.of());
        }
        return func;
    }


    @Nullable
    private FunctionImplementation get(Signature signature,
                                       List<DataType<?>> actualArgumentTypes,
                                       DataType<?> actualReturnType,
                                       Map<FunctionName, List<FunctionProvider>> candidatesByName) {
        var candidates = candidatesByName.get(signature.getName());
        if (candidates == null) {
            return null;
        }
        for (var candidate : candidates) {
            if (candidate.getSignature().equals(signature)) {
                var boundSignature = Signature.builder(signature)
                    .argumentTypes(Lists2.map(actualArgumentTypes, DataType::getTypeSignature))
                    .returnType(actualReturnType.getTypeSignature())
                    .build();
                return candidate.getFactory().apply(signature, boundSignature);
            }
        }
        return null;
    }

    @Nullable
    private static FunctionImplementation resolveFunctionBySignature(FunctionName name,
                                                                     List<Symbol> arguments,
                                                                     SearchPath searchPath,
                                                                     Map<FunctionName, List<FunctionProvider>> candidatesByName) {
        var candidates = candidatesByName.get(name);
        if (candidates == null && name.schema() == null) {
            for (String pathSchema : searchPath) {
                FunctionName searchPathFunctionName = new FunctionName(pathSchema, name.name());
                candidates = candidatesByName.get(searchPathFunctionName);
                if (candidates != null) {
                    break;
                }
            }
        }
        if (candidates != null) {
            assert candidates.stream().allMatch(f -> f.getSignature().getBindingInfo() != null) :
                "Resolving/Matching of signatures can only be done with non-null signature's binding info";

            @SuppressWarnings("ConstantConditions")
            // First lets try exact candidates, no generic type variables, no coercion allowed.
            var exactCandidates = candidates.stream()
                .filter(function -> function.getSignature().getBindingInfo().getTypeVariableConstraints().isEmpty())
                .collect(Collectors.toList());
            var match = matchFunctionCandidates(exactCandidates, arguments, SignatureBinder.CoercionType.NONE);
            if (match != null) {
                return match;
            }

            @SuppressWarnings("ConstantConditions")
            // Second, try candidates with generic type variables, still no coercion allowed.
            var genericCandidates = candidates.stream()
                .filter(function -> !function.getSignature().getBindingInfo().getTypeVariableConstraints().isEmpty())
                .collect(Collectors.toList());
            match = matchFunctionCandidates(genericCandidates, arguments, SignatureBinder.CoercionType.NONE);
            if (match != null) {
                return match;
            }

            @SuppressWarnings("ConstantConditions")
            // Third, try all candidates which allow coercion with precedence based coercion.
            var candidatesAllowingCoercion = candidates.stream()
                .filter(function -> function.getSignature().getBindingInfo().isCoercionAllowed())
                .collect(Collectors.toList());
            match = matchFunctionCandidates(
                candidatesAllowingCoercion,
                arguments,
                SignatureBinder.CoercionType.PRECEDENCE_ONLY
            );
            if (match != null) {
                return match;
            }

            // Last, try all candidates which allow coercion with full coercion.
            match = matchFunctionCandidates(candidatesAllowingCoercion, arguments, SignatureBinder.CoercionType.FULL);

            if (match == null) {
                raiseUnknownFunction(name.schema(), name.name(), arguments, candidates);
            }
            return match;
        }
        return null;
    }

    @Nullable
    private static FunctionImplementation matchFunctionCandidates(List<FunctionProvider> candidates,
                                                                  List<Symbol> arguments,
                                                                  SignatureBinder.CoercionType coercionType) {
        List<ApplicableFunction> applicableFunctions = new ArrayList<>();
        for (FunctionProvider candidate : candidates) {
            Signature boundSignature = new SignatureBinder(candidate.getSignature(), coercionType)
                .bind(Lists2.map(arguments, s -> s.valueType().getTypeSignature()));
            if (boundSignature != null) {
                applicableFunctions.add(
                    new ApplicableFunction(
                        candidate.getSignature(),
                        boundSignature,
                        candidate.getFactory()
                    )
                );
            }
        }

        if (coercionType != SignatureBinder.CoercionType.NONE) {
            applicableFunctions = selectMostSpecificFunctions(applicableFunctions, arguments);
            if (LOGGER.isDebugEnabled() && applicableFunctions.isEmpty()) {
                LOGGER.debug("At least single function must be left after selecting most specific one");
            }
        }

        if (applicableFunctions.size() == 1) {
            return getOnlyElement(applicableFunctions).get();
        }
        if (applicableFunctions.size() > 1) {
            if (LOGGER.isDebugEnabled()) {
                LOGGER.debug("Multiple candidates match! " + applicableFunctions);
            }
        }

        return null;
    }

    public FunctionImplementation getQualified(io.crate.expression.symbol.Function function,
                                               SearchPath searchPath) {
        var signature = function.signature();
        if (signature != null) {
            return getQualified(signature, Symbols.typeView(function.arguments()), function.valueType());
        }
        // Fallback to full function resolving
        // TODO: This is for BWC of older nodes, should be removed on the next major 5.0.
        return get(
            function.info().ident().fqnName().schema(),
            function.info().ident().fqnName().name(),
            function.arguments(),
            searchPath
            );
    }

    public FunctionImplementation getQualified(io.crate.expression.symbol.Aggregation function,
                                               SearchPath searchPath) {
        var signature = function.signature();
        if (signature != null) {
            return getQualified(signature, Symbols.typeView(function.inputs()), function.boundSignatureReturnType());
        }
        // Fallback to full function resolving
        // TODO: This is for BWC of older nodes, should be removed on the next major 5.0.
        return get(
            function.functionIdent().fqnName().schema(),
            function.functionIdent().fqnName().name(),
            function.inputs(),
            searchPath
            );
    }

    /**
     * Returns the function implementation for the given function signature.
     * First look up function in built-ins then fallback to user-defined functions.
     *
     * @param signature The function signature.
     * @return The function implementation.
     * @throws UnsupportedOperationException if no implementation is found.
     */
    public FunctionImplementation getQualified(Signature signature,
                                               List<DataType<?>> actualArgumentTypes,
                                               DataType<?> actualReturnType) throws UnsupportedOperationException {
        FunctionImplementation impl = get(signature, actualArgumentTypes, actualReturnType, functionImplementations);
        if (impl == null) {
            impl = get(signature, actualArgumentTypes, actualReturnType, udfFunctionImplementations);
        }
        return impl;
    }

    @VisibleForTesting
    static void raiseUnknownFunction(@Nullable String suppliedSchema,
                                     String name,
                                     List<Symbol> arguments,
                                     List<FunctionProvider> candidates) {
        List<DataType<?>> argumentTypes = Symbols.typeView(arguments);
        var function = new io.crate.expression.symbol.Function(
            Signature.builder()
                .name(new FunctionName(suppliedSchema, name))
                .argumentTypes(Lists2.map(argumentTypes, DataType::getTypeSignature))
                .returnType(DataTypes.UNDEFINED.getTypeSignature())
                .kind(FunctionType.SCALAR)
                .build(),
            arguments,
            DataTypes.UNDEFINED
        );

        var message = "Unknown function: " + function.toString(Style.QUALIFIED);
        if (candidates.isEmpty() == false) {
            if (arguments.isEmpty() == false) {
                message = message + ", no overload found for matching argument types: "
                          + "(" + Lists2.joinOn(", ", argumentTypes, DataType::toString) + ").";
            } else {
                message = message + ".";
            }
            message = message + " Possible candidates: "
                      + Lists2.joinOn(
                          ", ",
                          candidates,
                          c -> c.getSignature().getName().displayName()
                               + "("
                               + Lists2.joinOn(
                              ", ",
                              c.getSignature().getArgumentTypes(),
                              TypeSignature::toString)
                               + "):" + c.getSignature().getReturnType().toString())
                      ;
        }

        throw new UnsupportedOperationException(message);
    }

    private static List<ApplicableFunction> selectMostSpecificFunctions(List<ApplicableFunction> applicableFunctions,
                                                                        List<Symbol> arguments) {
        if (applicableFunctions.isEmpty()) {
            return applicableFunctions;
        }

        // Find most specific by number of exact argument type matches
        List<TypeSignature> argumentTypeSignatures = Lists2.map(arguments, s -> s.valueType().getTypeSignature());
        List<ApplicableFunction> mostSpecificFunctions = selectMostSpecificFunctions(
            applicableFunctions,
            (l, r) -> hasMoreExactTypeMatches(l, r, argumentTypeSignatures));
        if (mostSpecificFunctions.size() <= 1) {
            return mostSpecificFunctions;
        }

        // Find most specific by type precedence
        mostSpecificFunctions = selectMostSpecificFunctions(mostSpecificFunctions, Functions::isMoreSpecificThan);
        if (mostSpecificFunctions.size() <= 1) {
            return mostSpecificFunctions;
        }

        // If the return type for all the selected function is the same
        // all the functions are semantically the same. We can return just any of those.
        //
        // Second, if all given arguments are UNDEFINED, we also return the first one.
        // This may not be 100% correct, but e.g. for
        //
        //   `concat(null, null)`
        //
        // we currently have no other option as we cannot specify which one should match by signatures.
        //
        //     `concat(text, text):text`
        //     `concat(array(E), array(E)):array(E)`
        //
        if (returnTypeIsTheSame(mostSpecificFunctions)
            || arguments.stream().allMatch(s -> s.valueType().id() == DataTypes.UNDEFINED.id())) {
            ApplicableFunction selectedFunction = mostSpecificFunctions.stream()
                .sorted(Comparator.comparing(Objects::toString))
                .iterator().next();

            return List.of(selectedFunction);
        }
        return mostSpecificFunctions;
    }

    private static List<ApplicableFunction> selectMostSpecificFunctions(
        List<ApplicableFunction> candidates,
        BiFunction<ApplicableFunction, ApplicableFunction, Boolean> isMoreSpecific) {
        List<ApplicableFunction> representatives = new ArrayList<>();

        for (ApplicableFunction current : candidates) {
            boolean found = false;
            for (int i = 0; i < representatives.size(); i++) {
                ApplicableFunction representative = representatives.get(i);
                if (isMoreSpecific.apply(current, representative)) {
                    representatives.clear();
                    representatives.add(current);
                    found = true;
                    break;
                } else if (isMoreSpecific.apply(representative, current)) {
                    found = true;
                    break;
                }
            }

            if (!found) {
                representatives.add(current);
            }
        }

        return representatives;
    }

    /**
     * One method is more specific than another if invocation handled by the first method
     * could be passed on to the other one.
     * Additionally possible variadic type signatures are taken into account,
     * an exact amount of declared type signatures is more specific than expanded variadic type signatures.
     */
    private static boolean isMoreSpecificThan(ApplicableFunction left,
                                              ApplicableFunction right) {
        List<TypeSignature> resolvedTypes = left.getBoundSignature().getArgumentTypes();
        BoundVariables boundVariables = SignatureBinder.withPrecedenceOnly(right.getDeclaredSignature())
            .bindVariables(resolvedTypes);
        if (boundVariables == null) {
            return false;
        }

        int leftArgsCount = left.getDeclaredSignature().getArgumentTypes().size();
        int rightArgsCount = right.getDeclaredSignature().getArgumentTypes().size();
        return leftArgsCount >= rightArgsCount;
    }

    private static boolean hasMoreExactTypeMatches(ApplicableFunction left,
                                                   ApplicableFunction right,
                                                   List<TypeSignature> actualArgumentTypes) {
        int leftExactMatches = numberOfExactTypeMatches(
            actualArgumentTypes,
            left.getBoundSignature().getArgumentTypes()
        );
        int rightExactMatches = numberOfExactTypeMatches(
            actualArgumentTypes,
            right.getBoundSignature().getArgumentTypes()
        );
        return leftExactMatches > rightExactMatches;
    }

    private static boolean returnTypeIsTheSame(List<ApplicableFunction> applicableFunctions) {
        Set<DataType<?>> returnTypes = applicableFunctions.stream()
            .map(function -> function.getBoundSignature().getReturnType().createType())
            .collect(Collectors.toSet());
        return returnTypes.size() == 1;
    }

    private static int numberOfExactTypeMatches(List<TypeSignature> actualArgumentTypes,
                                                List<TypeSignature> declaredArgumentTypes) {
        int cnt = 0;
        for (int i = 0; i < actualArgumentTypes.size(); i++) {
            if (actualArgumentTypes.get(i).equals(declaredArgumentTypes.get(i))) {
                cnt++;
            }
        }
        return cnt;
    }

    private static class ApplicableFunction implements Supplier<FunctionImplementation> {

        private final Signature declaredSignature;
        private final Signature boundSignature;
        private final BiFunction<Signature, Signature, FunctionImplementation> factory;

        public ApplicableFunction(Signature declaredSignature,
                                  Signature boundSignature,
                                  BiFunction<Signature, Signature, FunctionImplementation> factory) {
            this.declaredSignature = declaredSignature;
            this.boundSignature = boundSignature;
            this.factory = factory;
        }

        public Signature getDeclaredSignature() {
            return declaredSignature;
        }

        public Signature getBoundSignature() {
            return boundSignature;
        }

        @Override
        public FunctionImplementation get() {
            return factory.apply(
                declaredSignature,
                boundSignature
            );
        }

        @Override
        public String toString() {
            return "ApplicableFunction{" +
                   "declaredSignature=" + declaredSignature +
                   ", boundSignature=" + boundSignature +
                   '}';
        }
    }
}
