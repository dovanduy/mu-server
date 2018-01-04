package ronin.muserver.rest;

import ronin.muserver.Method;

import javax.ws.rs.NotAllowedException;
import javax.ws.rs.NotFoundException;
import java.net.URI;
import java.util.*;

import static java.util.stream.Collectors.toList;
import static java.util.stream.Collectors.toSet;

/**
 * <p>
 * An implementation of section 3.7.2 of the JAX-RS 2.0 spec.
 * </p>
 * <p>
 * Note that some of part 2 of this section, i.e. "Obtain the object that will handle the request and a set of candidate methods"
 * is skipped due to the fact that sub-resource locators are not supported in this implementation.
 * </p>
 */
class RequestMatcher {

    static {
        MuRuntimeDelegate.ensureSet();
    }

    private final Set<ResourceClass> roots;

    RequestMatcher(Set<ResourceClass> roots) {
        if (roots == null) {
            throw new NullPointerException("roots cannot be null");
        }
        this.roots = roots;
    }

    public MatchedMethod findResourceMethod(Method httpMethod, URI uri) throws NotFoundException, NotAllowedException {
        StepOneOutput stepOneOutput = stepOneIdentifyASetOfCandidateRootResourceClassesMatchingTheRequest(uri);
        URI methodURI = stepOneOutput.unmatchedGroup == null ? null : URI.create(UriPattern.trimSlashes(stepOneOutput.unmatchedGroup));
        Set<MatchedMethod> candidateMethods = stepTwoObtainASetOfCandidateResourceMethodsForTheRequest(methodURI, stepOneOutput.candidates);
        return stepThreeIdentifyTheMethodThatWillHandleTheRequest(httpMethod, uri, candidateMethods);
    }

    public StepOneOutput stepOneIdentifyASetOfCandidateRootResourceClassesMatchingTheRequest(URI uri) throws NotFoundException {
        List<MatchedClass> candidates = roots.stream()
            .map(rc -> new MatchedClass(rc, rc.pathPattern.matcher(uri)))
            .filter(rc -> {
                PathMatch matcher = rc.pathMatch;
                // Remove members that do not match U.
                // Remove members for which the final regular expression capturing group value is neither empty nor ‘/’ and the class has no subresource methods or locators.
                return matcher.matches() && !(matcher.lastGroup() != null && rc.resourceClass.subResourceMethods().isEmpty());
            })
            .sorted((o1, o2) -> {
                UriPattern o1pp = o1.resourceClass.pathPattern;
                UriPattern o2pp = o2.resourceClass.pathPattern;
                // "Sort E using the number of literal characters in each member as the primary key (descending order)"
                int c = Integer.compare(o2pp.numberOfLiterals, o1pp.numberOfLiterals);
                if (c == 0) {
                    // "the number of capturing groups as a secondary key (descending order)"
                    c = Integer.compare(o2pp.namedGroups().size(), o1pp.namedGroups().size());
                }
                if (c == 0) {
                    // " and the number of capturing groups with non-default regular expressions (i.e. not ‘([ˆ/]+?)’) as the tertiary key (descending order)"
                    c = Integer.compare(countNonDefaultGroups(o2.resourceClass.pathTemplate), countNonDefaultGroups(o1.resourceClass.pathTemplate));
                }
                return c;
            })
            .collect(toList());
        if (candidates.isEmpty()) {
            throw new NotFoundException();
        }
        // Set Rmatch to be the first member of E and set U to be the value of the final capturing group of Rmatch when matched against U
        UriPattern rMatch = candidates.get(0).resourceClass.pathPattern;
        String u = rMatch.matcher(uri).lastGroup();

        // Let C0 be the set of classes Z such that R(TZ) = Rmatch. By definition, all root resource classes in C0 must be annotated with the same URI path template modulo variable names

        List<MatchedClass> c0 = candidates.stream()
            .filter(rc -> rc.resourceClass.pathPattern.equalModuloVariableNames(rMatch))
            .collect(toList());
        return new StepOneOutput(u, c0);
    }

    private Set<MatchedMethod> stepTwoObtainASetOfCandidateResourceMethodsForTheRequest(URI relativeUri, List<MatchedClass> candidateClasses) {
        if (relativeUri == null) {
            // handle section 3.7.2 - 2(a)
            Set<MatchedMethod> candidates = new HashSet<>();
            for (MatchedClass mc : candidateClasses) {
                for (ResourceMethod resourceMethod : mc.resourceClass.resourceMethods) {
                    if (!resourceMethod.isSubResource() && !resourceMethod.isSubResourceLocator()) {
                        MatchedMethod matchedMethod = new MatchedMethod(resourceMethod, true, mc.pathMatch.params());
                        candidates.add(matchedMethod);
                    }
                }
            }
            if (!candidates.isEmpty()) {
                return candidates;
            }
        }

        List<MatchedMethod> candidates = new ArrayList<>();
        for (MatchedClass candidateClass : candidateClasses) {
            for (ResourceMethod resourceMethod : candidateClass.resourceClass.resourceMethods) {
                if (resourceMethod.isSubResource() || resourceMethod.isSubResourceLocator()) {
                    PathMatch matcher = resourceMethod.pathPattern.matcher(relativeUri);
                    if (matcher.matches()) {
                        Map<String, String> combinedParams = new HashMap<>(candidateClass.pathMatch.params());
                        combinedParams.putAll(matcher.params());
                        candidates.add(new MatchedMethod(resourceMethod, true, combinedParams));
                    }
                }
            }
        }

        candidates.sort((o1, o2) -> {
            ResourceMethod rm1 = o1.resourceMethod;
            ResourceMethod rm2 = o2.resourceMethod;
            // "Sort E using the number of literal characters4 in each member as the primary key (descending order)"
            int c = Integer.compare(rm2.pathPattern.numberOfLiterals, rm1.pathPattern.numberOfLiterals);
            if (c == 0) {
                // "the number of capturing groups as a secondary key (descending order)"
                c = Integer.compare(rm2.pathPattern.namedGroups().size(), rm1.pathPattern.namedGroups().size());
            }
            if (c == 0) {
                // " and the number of capturing groups with non-default regular expressions (i.e. not ‘([ˆ/]+?)’) as the tertiary key (descending order)"
                c = Integer.compare(countNonDefaultGroups(rm2.pathTemplate), countNonDefaultGroups(rm1.pathTemplate));
            }
            if (c == 0) {
                // "and the source of each member as quaternary key sorting those derived from sub-resource methods ahead of those derived from sub-resource locators"
                // TODO: test that this is around the right way
                c = Boolean.compare(o1.resourceMethod.isSubResourceLocator(), o2.resourceMethod.isSubResourceLocator());
            }
            return c;
        });

        if (candidates.isEmpty()) {
            throw new NotFoundException();
        }

        UriPattern matcher = candidates.get(0).resourceMethod.pathPattern;
        Set<MatchedMethod> m = candidates.stream().filter(rm -> rm.resourceMethod.pathPattern.equals(matcher)).collect(toSet());
        if (!m.isEmpty()) {
            return m;
        }
        throw new NotFoundException();
    }

    static class MatchedClass {
        final ResourceClass resourceClass;
        final PathMatch pathMatch;

        MatchedClass(ResourceClass resourceClass, PathMatch pathMatch) {
            this.resourceClass = resourceClass;
            this.pathMatch = pathMatch;
        }
    }

    public static class MatchedMethod {
        public final ResourceMethod resourceMethod;
        final boolean isMatch;
        final Map<String, String> pathParams;

        MatchedMethod(ResourceMethod resourceMethod, boolean isMatch, Map<String, String> pathParams) {
            this.resourceMethod = resourceMethod;
            this.isMatch = isMatch;
            this.pathParams = pathParams;
        }
    }

    private MatchedMethod stepThreeIdentifyTheMethodThatWillHandleTheRequest(Method method, URI uri, Set<MatchedMethod> candidates) throws NotAllowedException {
        List<MatchedMethod> result = candidates.stream().filter(rm -> rm.resourceMethod.httpMethod == method).collect(toList());
        if (result.isEmpty()) {
            List<String> allowed = candidates.stream().map(c -> c.resourceMethod.httpMethod.name()).distinct().collect(toList());
            throw new NotAllowedException(allowed.get(0), allowed.subList(1, allowed.size()).toArray(new String[0]));
        }
        // TODO The media type of the request entity body (if any) is a supported input data format (see Section3.5). Ifnomethodssupportthemediatypeoftherequestentitybodyanimplementation MUST generate a NotSupportedException (415 status) and no entity.
        // TODO At least one of the acceptable response entity body media types is a supported output data format (see Section 3.5). If no methods support one of the acceptable response entity body media types an implementation MUST generate a NotAcceptableException (406 status) and no entity.
        // TODO order by media types
        return result.get(0);
    }

    static class StepOneOutput {
        final String unmatchedGroup;
        final List<MatchedClass> candidates;

        StepOneOutput(String unmatchedGroup, List<MatchedClass> candidates) {
            this.unmatchedGroup = unmatchedGroup;
            this.candidates = candidates;
        }
    }

    private int countNonDefaultGroups(String pathTemplate) {
        int count = 0;
        for (String bit : pathTemplate.split("/")) {
            if (bit.startsWith("{") && bit.endsWith("}") && bit.contains(":")) {
                count++;
            }
        }
        return count;
    }
}