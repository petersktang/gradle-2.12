/*
 * Copyright 2011 the original author or authors.
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
package org.gradle.api.internal.artifacts.ivyservice.resolveengine
import org.apache.ivy.core.module.descriptor.DefaultExcludeRule
import org.apache.ivy.core.module.id.ArtifactId
import org.apache.ivy.core.module.id.ModuleId
import org.apache.ivy.core.module.id.ModuleRevisionId
import org.apache.ivy.plugins.matcher.ExactPatternMatcher
import org.apache.ivy.plugins.matcher.PatternMatcher
import org.gradle.api.artifacts.*
import org.gradle.api.artifacts.component.ComponentIdentifier
import org.gradle.api.artifacts.component.ComponentSelector
import org.gradle.api.internal.artifacts.DefaultModuleIdentifier
import org.gradle.api.internal.artifacts.DefaultModuleVersionSelector
import org.gradle.api.internal.artifacts.configurations.ConfigurationInternal
import org.gradle.api.internal.artifacts.dsl.ModuleReplacementsData
import org.gradle.api.internal.artifacts.ivyservice.CacheLockingManager
import org.gradle.api.internal.artifacts.ivyservice.DefaultLenientConfiguration
import org.gradle.api.internal.artifacts.ivyservice.resolveengine.artifact.DefaultResolvedArtifactsBuilder
import org.gradle.api.internal.artifacts.ivyservice.resolveengine.artifact.ResolvedArtifactsGraphVisitor
import org.gradle.api.internal.artifacts.ivyservice.resolveengine.graph.CompositeDependencyArtifactsVisitor
import org.gradle.api.internal.artifacts.ivyservice.resolveengine.graph.CompositeDependencyGraphVisitor
import org.gradle.api.internal.artifacts.ivyservice.resolveengine.graph.DependencyGraphBuilder
import org.gradle.api.internal.artifacts.ivyservice.resolveengine.graph.conflicts.DefaultConflictHandler
import org.gradle.api.internal.artifacts.ivyservice.resolveengine.oldresult.*
import org.gradle.api.internal.artifacts.ivyservice.resolveengine.projectresult.ResolvedLocalComponentsResultBuilder
import org.gradle.api.internal.artifacts.ivyservice.resolveengine.projectresult.ResolvedLocalComponentsResultGraphVisitor
import org.gradle.api.internal.artifacts.ivyservice.resolveengine.result.DummyBinaryStore
import org.gradle.api.internal.artifacts.ivyservice.resolveengine.result.DummyStore
import org.gradle.api.internal.artifacts.ivyservice.resolveengine.result.ResolutionResultBuilder
import org.gradle.api.internal.artifacts.ivyservice.resolveengine.result.ResolutionResultDependencyGraphVisitor
import org.gradle.api.internal.artifacts.publish.DefaultPublishArtifact
import org.gradle.api.internal.tasks.DefaultTaskDependency
import org.gradle.api.specs.Spec
import org.gradle.internal.component.external.model.DefaultModuleComponentIdentifier
import org.gradle.internal.component.external.model.DefaultModuleComponentSelector
import org.gradle.internal.component.local.model.DefaultLocalComponentMetaData
import org.gradle.internal.component.local.model.DefaultProjectComponentIdentifier
import org.gradle.internal.component.local.model.DslOriginDependencyMetaDataWrapper
import org.gradle.internal.component.model.*
import org.gradle.internal.resolve.ModuleVersionNotFoundException
import org.gradle.internal.resolve.ModuleVersionResolveException
import org.gradle.internal.resolve.resolver.ArtifactResolver
import org.gradle.internal.resolve.resolver.ComponentMetaDataResolver
import org.gradle.internal.resolve.resolver.DependencyToComponentIdResolver
import org.gradle.internal.resolve.resolver.ResolveContextToComponentResolver
import org.gradle.internal.resolve.result.BuildableArtifactSetResolveResult
import org.gradle.internal.resolve.result.BuildableComponentIdResolveResult
import org.gradle.internal.resolve.result.BuildableComponentResolveResult
import spock.lang.Specification

import static org.gradle.api.internal.artifacts.DefaultModuleVersionIdentifier.newId
import static org.gradle.api.internal.artifacts.DefaultModuleVersionSelector.newSelector

class DependencyGraphBuilderTest extends Specification {
    def configuration = Mock(ConfigurationInternal)
    def conflictResolver = Mock(ModuleConflictResolver)
    def idResolver = Mock(DependencyToComponentIdResolver)
    def metaDataResolver = Mock(ComponentMetaDataResolver)
    def artifactResolver = Mock(ArtifactResolver)
    def resolutionResultBuilder = Mock(ResolutionResultBuilder)
    def projectModelBuilder = Mock(ResolvedLocalComponentsResultBuilder)
    def root = project('root', '1.0', ['root'])
    def moduleResolver = Mock(ResolveContextToComponentResolver)
    def dependencyToConfigurationResolver = new DefaultDependencyToConfigurationResolver()
    def moduleReplacements = Mock(ModuleReplacementsData)
    DependencyGraphBuilder builder

    def setup() {
        _ * configuration.name >> 'root'
        _ * configuration.path >> 'root'
        _ * moduleResolver.resolve(_, _) >> { it[1].resolved(root) }
        _ * artifactResolver.resolveModuleArtifacts(_, _, _,) >> { ComponentResolveMetaData module, ComponentUsage context, BuildableArtifactSetResolveResult result ->
            result.resolved(module.getConfiguration(context.configurationName).artifacts)
        }

        builder = new DependencyGraphBuilder(idResolver, metaDataResolver, moduleResolver, dependencyToConfigurationResolver, new DefaultConflictHandler(conflictResolver, moduleReplacements))
    }

    private DefaultLenientConfiguration resolve() {
        def transientConfigurationResultsBuilder = new TransientConfigurationResultsBuilder(new DummyBinaryStore(), new DummyStore())
        def modelBuilder = new DefaultResolvedConfigurationBuilder(transientConfigurationResultsBuilder)
        def configurationResultVisitor = new ResolvedConfigurationDependencyGraphVisitor(modelBuilder)

        def resolutionResultVisitor = new ResolutionResultDependencyGraphVisitor(resolutionResultBuilder)
        def projectComponentsVisitor = new ResolvedLocalComponentsResultGraphVisitor(projectModelBuilder)

        def artifactsBuilder = new DefaultResolvedArtifactsBuilder()
        def artifactsGraphVisitor = new ResolvedArtifactsGraphVisitor(new CompositeDependencyArtifactsVisitor(artifactsBuilder, configurationResultVisitor), artifactResolver)

        def graphVisitor = new CompositeDependencyGraphVisitor(configurationResultVisitor, resolutionResultVisitor, projectComponentsVisitor, artifactsGraphVisitor)

        builder.resolve(configuration, graphVisitor)

        def graphResults = modelBuilder.complete()
        def artifactResults = artifactsBuilder.resolve()

        new DefaultLenientConfiguration(configuration, Stub(CacheLockingManager), graphResults.getUnresolvedDependencies(),
                artifactResults, new TransientConfigurationResultsLoader(transientConfigurationResultsBuilder, graphResults, artifactResults))
    }

    def "does not resolve a given module selector more than once"() {
        given:
        def a = revision("a")
        def b = revision("b")
        def c = revision("c")
        traverses root, a
        traverses root, b
        traverses a, c
        doesNotResolve b, c

        when:
        def result = resolve()
        result.rethrowFailure()

        then:
        modules(result) == ids(a, b, c)
    }

    def "correctly notifies the resolution result builder"() {
        given:
        def a = revision("a")
        def b = revision("b")
        def c = revision("c")
        def d = revision("d")
        traverses root, a
        traverses root, b
        traverses a, c
        traversesMissing a, d

        when:
        resolve()

        then:
        1 * resolutionResultBuilder.start(newId("group", "root", "1.0"), new DefaultProjectComponentIdentifier(":root"))
        then:
        1 * resolutionResultBuilder.resolvedConfiguration({ it.name == 'root' }, { it*.requested.module == ['a', 'b'] })
        then:
        1 * resolutionResultBuilder.resolvedConfiguration({ it.name == 'a' }, { it*.requested.module == ['c', 'd'] && it*.failure.count { it != null } == 1 })
    }

    def "correctly notifies the project configuration result builder"() {
        given:
        def a = project("a")
        def b = project("b")
        def c = project("c")
        def d = revision("d")
        traverses root, a
        traverses root, b
        traverses a, c
        traversesMissing a, d

        when:
        resolve()

        then:
        1 * projectModelBuilder.projectConfigurationResolved({ it.projectPath == ':a'}, { it == 'default' })
        1 * projectModelBuilder.localComponentResolved({ it.projectPath == ':a'}, _)
        1 * projectModelBuilder.projectConfigurationResolved({ it.projectPath == ':b'}, { it == 'default' })
        1 * projectModelBuilder.localComponentResolved({ it.projectPath == ':b'}, _)
        1 * projectModelBuilder.projectConfigurationResolved({ it.projectPath == ':c'}, { it == 'default' })
        1 * projectModelBuilder.localComponentResolved({ it.projectPath == ':c'}, _)
        0 * projectModelBuilder._
    }

    def "honors component replacements"() {
        given:
        def a = revision('a') // a->c
        def b = revision('b') // b->d, replaces a
        def c = revision('c') //transitive of evicted a
        def d = revision('d')

        traverses root, a
        traverses root, b
        doesNotResolve a, c
        traverses b, d

        moduleReplacements.getReplacementFor(new DefaultModuleIdentifier("group", "a")) >> new DefaultModuleIdentifier("group", "b")
        1 * conflictResolver.select(!null) >> {
            Collection<ComponentResolutionState> candidates = it[0]
            def sel = candidates.find { it.id.name == 'b' }
            assert sel
            sel
        }
        0 * conflictResolver._

        when:
        def result = resolve()
        result.rethrowFailure()

        then:
        modules(result) == ids(b, d)
    }

    def "does not resolve a given dynamic module selector more than once"() {
        given:
        def a = revision("a")
        def b = revision("b")
        def c = revision("c")
        def d = revision("d")
        traverses root, a
        traverses root, b
        traverses root, c
        traverses a, d, revision: 'latest'
        doesNotResolve b, d, revision: 'latest'
        doesNotTraverse c, d

        when:
        def result = resolve()
        result.rethrowFailure()

        then:
        modules(result) == ids(a, b, c, d)
    }

    def "does not include evicted module when selected module already traversed before conflict detected"() {
        given:
        def selected = revision('a', '1.2')
        def evicted = revision('a', '1.1')
        def b = revision('b')
        def c = revision('c')
        def d = revision('d')
        def e = revision('e')
        traverses root, selected
        traverses selected, c
        traverses root, b
        traverses b, d
        doesNotTraverse d, evicted // Conflict is deeper than all dependencies of selected module
        doesNotResolve evicted, e

        when:
        def result = resolve()
        result.rethrowFailure()

        then:
        1 * conflictResolver.select(!null) >> {
            Collection<ComponentResolutionState> candidates = it[0]
            assert candidates*.version == ['1.2', '1.1']
            return candidates.find { it.version == '1.2' }
        }
        0 * conflictResolver._

        and:
        modules(result) == ids(selected, b, c, d)
    }

    def "does not include evicted module when evicted module already traversed before conflict detected"() {
        given:
        def selected = revision('a', '1.2')
        def evicted = revision('a', '1.1')
        def b = revision('b')
        def c = revision('c')
        def d = revision('d')
        def e = revision('e')
        traverses root, evicted
        traverses evicted, c
        traverses root, b
        traverses b, d
        traverses d, selected // Conflict is deeper than all dependencies of other module
        traverses selected, e

        when:
        def result = resolve()
        result.rethrowFailure()

        then:
        1 * conflictResolver.select(!null) >> {
            Collection<ComponentResolutionState> candidates = it[0]
            assert candidates*.version == ['1.1', '1.2']
            return candidates.find { it.version == '1.2' }
        }
        0 * conflictResolver._

        and:
        modules(result) == ids(selected, b, d, e)
    }

    def "does not include evicted module when path through evicted module is queued for traversal when conflict detected"() {
        given:
        def selected = revision('a', '1.2')
        def evicted = revision('a', '1.1')
        def b = revision('b')
        def c = revision('c')
        def d = revision('d')
        def e = revision('e')
        traverses root, evicted
        traverses evicted, c
        doesNotResolve c, d
        traverses root, b
        traverses b, selected
        traverses selected, e

        when:
        def result = resolve()
        result.rethrowFailure()

        then:
        1 * conflictResolver.select(!null) >> {
            Collection<ComponentResolutionState> candidates = it[0]
            assert candidates*.version == ['1.1', '1.2']
            return candidates.find { it.version == '1.2' }
        }
        0 * conflictResolver._

        and:
        modules(result) == ids(selected, b, e)
    }

    def "resolves when path through selected module is queued for traversal when conflict detected"() {
        given:
        def selected = revision('a', '1.2')
        def evicted = revision('a', '1.1')
        def b = revision('b')
        def c = revision('c')
        traverses root, selected
        traverses selected, b
        doesNotTraverse root, evicted
        doesNotResolve evicted, c

        when:
        def result = resolve()
        result.rethrowFailure()

        then:
        1 * conflictResolver.select(!null) >> {
            Collection<ComponentResolutionState> candidates = it[0]
            assert candidates*.version == ['1.2', '1.1']
            return candidates.find { it.version == '1.2' }
        }
        0 * conflictResolver._

        and:
        modules(result) == ids(selected, b)
    }

    def "does not include evicted module when another path through evicted module traversed after conflict detected"() {
        given:
        def selected = revision('a', '1.2')
        def evicted = revision('a', '1.1')
        def b = revision('b')
        def c = revision('c')
        def d = revision('d')
        traverses root, evicted
        doesNotResolve evicted, d
        traverses root, selected
        traverses selected, c
        traverses root, b
        doesNotResolve b, evicted

        when:
        def result = resolve()
        result.rethrowFailure()

        then:
        1 * conflictResolver.select(!null) >> {
            Collection<ComponentResolutionState> candidates = it[0]
            assert candidates*.version == ['1.1', '1.2']
            return candidates.find { it.version == '1.2' }
        }
        0 * conflictResolver._

        and:
        modules(result) == ids(selected, b, c)
    }

    def "restarts conflict resolution when later conflict on same module discovered"() {
        given:
        def selectedA = revision('a', '1.2')
        def evictedA1 = revision('a', '1.1')
        def evictedA2 = revision('a', '1.0')
        def selectedB = revision('b', '2.2')
        def evictedB = revision('b', '2.1')
        def c = revision('c')
        traverses root, evictedA1
        traverses root, selectedA
        traverses selectedA, c
        traverses root, evictedB
        traverses root, selectedB
        doesNotTraverse selectedB, evictedA2

        when:
        def result = resolve()
        result.rethrowFailure()

        then:
        1 * conflictResolver.select({ it*.version == ['1.1', '1.2'] }) >> {
            Collection<ComponentResolutionState> candidates = it[0]
            return candidates.find { it.version == '1.2' }
        }
        1 * conflictResolver.select({ it*.version == ['2.1', '2.2'] }) >> {
            Collection<ComponentResolutionState> candidates = it[0]
            return candidates.find { it.version == '2.2' }
        }
        1 * conflictResolver.select({ it*.version == ['1.1', '1.2', '1.0'] }) >> {
            Collection<ComponentResolutionState> candidates = it[0]
            return candidates.find { it.version == '1.2' }
        }
        0 * conflictResolver._

        and:
        modules(result) == ids(selectedA, c, selectedB)
    }

    def "does not include module version that is excluded after conflict resolution has been applied"() {
        given:
        def a = revision('a')
        def b = revision('b')
        def evicted = revision('c', '1')
        def selected = revision('c', '2')
        def d = revision('d')
        def e = revision('e')
        traverses root, evicted
        traverses root, a, exclude: b
        doesNotResolve evicted, a
        traverses a, b
        traverses root, d
        traverses d, e
        traverses e, selected // conflict is deeper than 'b', to ensure 'b' has been visited

        when:
        def result = resolve()
        result.rethrowFailure()

        then:
        1 * conflictResolver.select({ it*.version == ['1', '2'] }) >> {
            Collection<ComponentResolutionState> candidates = it[0]
            return candidates.find { it.version == '2' }
        }
        0 * conflictResolver._

        and:
        modules(result) == ids(a, selected, d, e)
    }

    def "does not include dependencies of module version that is no longer transitive after conflict resolution has been applied"() {
        given:
        def a = revision('a')
        def b = revision('b')
        def evicted = revision('c', '1')
        def selected = revision('c', '2')
        def d = revision('d')
        def e = revision('e')
        traverses root, evicted
        traverses root, a, transitive: false
        doesNotResolve evicted, a
        traverses a, b
        traverses root, d
        traverses d, e
        traverses e, selected // conflict is deeper than 'b', to ensure 'b' has been visited

        when:
        def result = resolve()
        result.rethrowFailure()

        then:
        1 * conflictResolver.select({ it*.version == ['1', '2'] }) >> {
            Collection<ComponentResolutionState> candidates = it[0]
            return candidates.find { it.version == '2' }
        }
        0 * conflictResolver._

        and:
        modules(result) == ids(a, selected, d, e)
    }

    def "does not attempt to resolve a dependency whose target module is excluded earlier in the path"() {
        given:
        def a = revision('a')
        def b = revision('b')
        def c = revision('c')
        traverses root, a
        traverses a, b, exclude: c
        doesNotResolve b, c

        when:
        def result = resolve()
        result.rethrowFailure()

        then:
        modules(result) == ids(a, b)
    }

    def "does not include the artifacts of evicted modules"() {
        given:
        def selected = revision('a', '1.2')
        def evicted = revision('a', '1.1')
        traverses root, selected
        doesNotTraverse root, evicted

        when:
        def result = resolve()
        result.rethrowFailure()

        then:
        1 * conflictResolver.select(!null) >> {
            Collection<ComponentResolutionState> candidates = it[0]
            return candidates.find { it.version == '1.2' }
        }

        and:
        artifacts(result) == ids(selected)
    }

    def "does not include the artifacts of excluded modules when excluded by all paths"() {
        given:
        def a = revision('a')
        def b = revision('b')
        def c = revision('c')
        def d = revision('d')
        traverses root, a
        traverses a, b, exclude: c
        doesNotResolve b, c
        traverses root, d, exclude: c
        doesNotResolve d, c

        when:
        def result = resolve()
        result.rethrowFailure()

        then:
        modules(result) == ids(a, b, d)
        artifacts(result) == ids(a, b, d)
    }

    def "includes a module version when there is a path to the version that does not exclude it"() {
        given:
        def a = revision('a')
        def b = revision('b')
        def c = revision('c')
        def d = revision('d')
        traverses root, a
        traverses a, b, exclude: c
        doesNotResolve b, c
        traverses root, d
        traverses d, c

        when:
        def result = resolve()
        result.rethrowFailure()

        then:
        modules(result) == ids(a, b, c, d)
        artifacts(result) == ids(a, b, c, d)
    }

    def "ignores a new incoming path that includes a subset of those already included"() {
        given:
        def a = revision('a')
        def b = revision('b')
        def c = revision('c')
        traverses root, a
        traverses a, b
        traverses root, c, exclude: b
        doesNotResolve c, a

        when:
        def result = resolve()
        result.rethrowFailure()

        then:
        modules(result) == ids(a, b, c)
        artifacts(result) == ids(a, b, c)
    }

    def "ignores a new incoming path that includes the same set of module versions"() {
        given:
        def a = revision('a')
        def b = revision('b')
        def c = revision('c')
        def d = revision('d')
        def e = revision('e')
        traverses root, a, exclude: e
        traverses a, b
        traverses a, c
        traverses b, d
        doesNotResolve c, d
        doesNotResolve d, e

        when:
        def result = resolve()
        result.rethrowFailure()

        then:
        modules(result) == ids(a, b, c, d)
        artifacts(result) == ids(a, b, c, d)
    }

    def "restarts traversal when new incoming path excludes fewer module versions"() {
        given:
        def a = revision('a')
        def b = revision('b')
        def c = revision('c')
        traverses root, a, exclude: b
        traverses root, c
        doesNotResolve c, a
        traverses a, b

        when:
        def result = resolve()
        result.rethrowFailure()

        then:
        modules(result) == ids(a, b, c)
        artifacts(result) == ids(a, b, c)
    }

    def "does not traverse outgoing paths of a non-transitive dependency"() {
        given:
        def a = revision('a')
        def b = revision('b')
        def c = revision('c')
        traverses root, a
        traverses a, b, transitive: false
        doesNotResolve b, c

        when:
        def result = resolve()
        result.rethrowFailure()

        then:
        modules(result) == ids(a, b)
        artifacts(result) == ids(a, b)
    }

    def "reports shortest incoming paths for a failed dependency"() {
        given:
        def a = revision('a')
        def b = revision('b')
        def c = revision('c')
        traverses root, a
        traverses root, b
        doesNotResolve b, a
        traversesBroken a, c
        doesNotResolve b, c

        when:
        def result = resolve()

        then:
        result.unresolvedModuleDependencies.size() == 1
        def unresolved = result.unresolvedModuleDependencies.iterator().next()
        unresolved.selector == new DefaultModuleVersionSelector('group', 'c', '1.0')
        unresolved.problem instanceof ModuleVersionResolveException

        when:
        result.rethrowFailure()

        then:
        ResolveException e = thrown()
        e.cause instanceof ModuleVersionResolveException
        e.cause.message.contains "group:root:1.0 > group:a:1.0"
        e.cause.message.contains "group:root:1.0 > group:b:1.0"
        !e.cause.message.contains("group:root:1.0 > group:b:1.0 > group:a:1.0")
    }

    def "reports failure to resolve version selector to module version"() {
        given:
        def a = revision('a')
        def b = revision('b')
        traverses root, a
        traverses root, b
        doesNotResolve b, a
        brokenSelector a, 'unknown'
        doesNotResolve b, revision('unknown')

        when:
        def result = resolve()

        then:
        result.unresolvedModuleDependencies.size() == 1
        def unresolved = result.unresolvedModuleDependencies.iterator().next()
        unresolved.selector == new DefaultModuleVersionSelector('group', 'unknown', '1.0')
        unresolved.problem instanceof ModuleVersionResolveException

        when:
        result.rethrowFailure()

        then:
        ResolveException e = thrown()
        e.cause instanceof ModuleVersionResolveException
        e.cause.message.contains "group:root:1.0 > group:a:1.0"
        e.cause.message.contains "group:root:1.0 > group:b:1.0"
    }

    def "merges all failures for all dependencies with a given module version selector"() {
        given:
        def a = revision('a')
        def b = revision('b')
        def c = revision('c')
        traverses root, a
        traverses root, b
        traversesBroken a, c
        doesNotResolve b, c

        when:
        def result = resolve()

        then:
        result.unresolvedModuleDependencies.size() == 1
        def unresolved = result.unresolvedModuleDependencies.iterator().next()
        unresolved.selector == new DefaultModuleVersionSelector('group', 'c', '1.0')
        unresolved.problem instanceof ModuleVersionResolveException

        when:
        result.rethrowFailure()

        then:
        ResolveException e = thrown()
        e.cause instanceof ModuleVersionResolveException
        e.cause.message.contains "group:root:1.0 > group:a:1.0"
        e.cause.message.contains "group:root:1.0 > group:b:1.0"
    }

    def "reports shortest incoming paths for a missing module version"() {
        given:
        def a = revision('a')
        def b = revision('b')
        def c = revision('c')
        traverses root, a
        traverses root, b
        doesNotResolve b, a
        traversesMissing a, c
        doesNotResolve b, c

        when:
        def result = resolve()

        then:
        result.unresolvedModuleDependencies.size() == 1
        def unresolved = result.unresolvedModuleDependencies.iterator().next()
        unresolved.selector == new DefaultModuleVersionSelector('group', 'c', '1.0')
        unresolved.problem instanceof ModuleVersionNotFoundException

        when:
        result.rethrowFailure()

        then:
        ResolveException e = thrown()
        e.cause instanceof ModuleVersionNotFoundException
        e.cause.message.contains "group:root:1.0 > group:a:1.0"
        e.cause.message.contains "group:root:1.0 > group:b:1.0"
        !e.cause.message.contains("group:root:1.0 > group:b:1.0 > group:a:1.0")
    }

    def "merges all dependencies with a given module version selector when reporting missing version"() {
        given:
        def a = revision('a')
        def b = revision('b')
        def c = revision('c')
        traverses root, a
        traverses root, b
        traversesMissing a, c
        doesNotResolve b, c

        when:
        def result = resolve()

        then:
        result.unresolvedModuleDependencies.size() == 1
        def unresolved = result.unresolvedModuleDependencies.iterator().next()
        unresolved.selector == new DefaultModuleVersionSelector('group', 'c', '1.0')
        unresolved.problem instanceof ModuleVersionResolveException

        when:
        result.rethrowFailure()

        then:
        ResolveException e = thrown()
        e.cause instanceof ModuleVersionNotFoundException
        e.cause.message.contains "group:root:1.0 > group:a:1.0"
        e.cause.message.contains "group:root:1.0 > group:b:1.0"
    }

    def "can handle a cycle in the incoming paths of a broken module"() {
        given:
        def a = revision('a')
        def b = revision('b')
        def c = revision('c')
        traverses root, a
        traverses a, b
        doesNotResolve b, a
        traversesMissing b, c

        when:
        def result = resolve()

        then:
        result.unresolvedModuleDependencies.size() == 1
        def unresolved = result.unresolvedModuleDependencies.iterator().next()
        unresolved.selector == new DefaultModuleVersionSelector('group', 'c', '1.0')
        unresolved.problem instanceof ModuleVersionResolveException

        when:
        result.rethrowFailure()

        then:
        ResolveException e = thrown()
        e.cause instanceof ModuleVersionNotFoundException
        e.cause.message.contains "group:root:1.0 > group:a:1.0 > group:b:1.0"
    }

    def "does not report a path through an evicted version"() {
        given:
        def selected = revision('a', '1.2')
        def evicted = revision('a', '1.1')
        def b = revision('b')
        def c = revision('c')
        def d = revision('d')
        def e = revision('e')
        traverses root, evicted
        traverses evicted, b
        traversesMissing b, c
        traverses root, d
        traverses d, e
        traverses e, selected
        doesNotResolve selected, c

        when:
        def result = resolve()

        then:
        1 * conflictResolver.select(!null) >> {
            Collection<ComponentResolutionState> candidates = it[0]
            return candidates.find { it.version == '1.2' }
        }

        when:
        result.rethrowFailure()

        then:
        ResolveException ex = thrown()
        ex.cause instanceof ModuleVersionNotFoundException
        !ex.cause.message.contains("group:a:1.1")
        ex.cause.message.contains "group:root:1.0 > group:a:1.2"

        and:
        modules(result) == ids(selected, d, e)
    }

    def "fails when conflict resolution selects a version that does not exist"() {
        given:
        def selected = revision('a', '1.2')
        def evicted = revision('a', '1.1')
        def b = revision('b')
        traverses root, evicted
        traverses root, b
        traversesMissing b, selected

        when:
        def result = resolve()
        result.rethrowFailure()

        then:
        1 * conflictResolver.select(!null) >> {
            Collection<ComponentResolutionState> candidates = it[0]
            return candidates.find { it.version == '1.2' }
        }

        and:
        ResolveException e = thrown()
        e.cause instanceof ModuleVersionNotFoundException
        e.cause.message.contains("group:root:1.0")
    }

    def "does not fail when conflict resolution evicts a version that does not exist"() {
        given:
        def selected = revision('a', '1.2')
        def evicted = revision('a', '1.1')
        def b = revision('b')
        traversesMissing root, evicted
        traverses root, b
        traverses b, selected

        when:
        def result = resolve()
        result.rethrowFailure()

        then:
        1 * conflictResolver.select(!null) >> {
            Collection<ComponentResolutionState> candidates = it[0]
            return candidates.find { it.version == '1.2' }
        }

        and:
        modules(result) == ids(selected, b)
    }

    def "does not fail when a broken version is evicted"() {
        given:
        def selected = revision('a', '1.2')
        def evicted = revision('a', '1.1')
        def b = revision('b')
        def c = revision('c')
        traverses root, evicted
        traversesBroken evicted, b
        traverses root, c
        traverses c, selected

        when:
        def result = resolve()
        result.rethrowFailure()

        then:
        1 * conflictResolver.select(!null) >> {
            Collection<ComponentResolutionState> candidates = it[0]
            return candidates.find { it.version == '1.2' }
        }

        and:
        modules(result) == ids(selected, c)
    }

    def "direct dependency can force a particular version"() {
        given:
        def forced = revision("a", "1")
        def evicted = revision("a", "2")
        def b = revision("b")
        traverses root, b
        traverses root, forced, force: true
        doesNotTraverse b, evicted

        when:
        def result = resolve()
        result.rethrowFailure()

        then:
        modules(result) == ids(forced, b)
    }

    def revision(String name, String revision = '1.0') {
        // TODO Shouldn't really be using the local component implementation here
        def id = newId("group", name, revision)
        def metaData = new DefaultLocalComponentMetaData(id, DefaultModuleComponentIdentifier.newId(id), "release")
        metaData.addConfiguration("default", "defaultConfig", [] as Set<String>, ["default"] as Set<String>, true, true, new DefaultTaskDependency())
        metaData.addArtifacts("default", [new DefaultPublishArtifact("art1", "zip", "art", null, new Date(), new File("art1.zip"))])
//        def descriptor = new DefaultModuleDescriptor(createModuleRevisionId("group", name, revision), "release", new Date())
//        def metaData = new MutableModuleMetaData(descriptor)
//        metaData.descriptor.addConfiguration(new org.apache.ivy.core.module.descriptor.Configuration('default', org.apache.ivy.core.module.descriptor.Configuration.Visibility.PUBLIC, null, [] as String[], true, null))
//        descriptor.addArtifact('default', new DefaultArtifact(descriptor.moduleRevisionId, new Date(), "art1", "art", "zip"))
        return metaData
    }

    def project(String name, String revision = '1.0', List<String> extraConfigs = []) {
        def metaData = new DefaultLocalComponentMetaData(newId("group", name, revision), DefaultProjectComponentIdentifier.newId(":${name}"), "release")
        metaData.addConfiguration("default", "defaultConfig", [] as Set<String>, ["default"] as Set<String>, true, true, new DefaultTaskDependency())
        extraConfigs.each { String config ->
            metaData.addConfiguration(config, "${config}Config", ["default"] as Set<String>, ["default", config] as Set<String>, true, true, new DefaultTaskDependency())
        }
        metaData.addArtifacts("default", [new DefaultPublishArtifact("art1", "zip", "art", null, new Date(), new File("art1.zip"))])
        return metaData
    }

    def traverses(Map<String, ?> args = [:], def from, ComponentResolveMetaData to) {
        def dependencyMetaData = dependsOn(args, from, to.id)
        selectorResolvesTo(dependencyMetaData, to.componentId, to.id)

        1 * metaDataResolver.resolve(to.componentId, _, _) >> { ComponentIdentifier id, ComponentOverrideMetadata requestMetaData, BuildableComponentResolveResult result ->
            result.resolved(to)
        }
    }

    def doesNotTraverse(Map<String, ?> args = [:], def from, ComponentResolveMetaData to) {
        def dependencyMetaData = dependsOn(args, from, to.id)
        selectorResolvesTo(dependencyMetaData, to.componentId, to.id)
        0 * metaDataResolver.resolve(to.componentId, _, _)
    }

    def doesNotResolve(Map<String, ?> args = [:], def from, ComponentResolveMetaData to) {
        def dependencyMetaData = dependsOn(args, from, to.id)
        0 * idResolver.resolve(dependencyMetaData, _)
        0 * metaDataResolver.resolve(to.componentId, _, _)
    }

    def traversesMissing(Map<String, ?> args = [:], def from, ComponentResolveMetaData to) {
        def dependencyMetaData = dependsOn(args, from, to.id)
        selectorResolvesTo(dependencyMetaData, to.componentId, to.id)
        1 * metaDataResolver.resolve(to.componentId, _, _) >> { ComponentIdentifier id, ComponentOverrideMetadata requestMetaData, BuildableComponentResolveResult result ->
            result.notFound(to.componentId)
        }
    }

    def traversesBroken(Map<String, ?> args = [:], def from, ComponentResolveMetaData to) {
        def dependencyMetaData = dependsOn(args, from, to.id)
        selectorResolvesTo(dependencyMetaData, to.componentId, to.id)
        1 * metaDataResolver.resolve(to.componentId, _, _) >> { ComponentIdentifier id, ComponentOverrideMetadata requestMetaData, BuildableComponentResolveResult result ->
            result.failed(new ModuleVersionResolveException(newSelector("a", "b", "c"), "broken"))
        }
    }

    ModuleVersionIdentifier toModuleVersionIdentifier(ModuleRevisionId moduleRevisionId) {
        ModuleVersionIdentifier moduleVersionIdentifier = Mock();
        (0..2) * moduleVersionIdentifier.group >> moduleRevisionId.organisation;
        (0..2) * moduleVersionIdentifier.name >> moduleRevisionId.name;
        (0..2) * moduleVersionIdentifier.version >> moduleRevisionId.revision;
        moduleVersionIdentifier
    }

    def brokenSelector(Map<String, ?> args = [:], def from, String to) {
        def dependencyMetaData = dependsOn(args, from, newId("group", to, "1.0"))
        1 * idResolver.resolve(dependencyMetaData, _) >> { DependencyMetaData dep, BuildableComponentIdResolveResult result ->
            result.failed(new ModuleVersionResolveException(newSelector("a", "b", "c"), "broken"))
        }
    }

    def dependsOn(Map<String, ?> args = [:], ComponentResolveMetaData from, ModuleVersionIdentifier to) {
        ModuleVersionIdentifier dependencyId = args.revision ? newId(to.group, to.name, args.revision) : to
        boolean transitive = args.transitive == null || args.transitive
        boolean force = args.force
        ModuleVersionSelector selector = newSelector(dependencyId.group, dependencyId.name, dependencyId.version)
        ComponentSelector componentSelector = DefaultModuleComponentSelector.newSelector(selector)
        def excludeRules = []
        if (args.exclude) {
            ComponentResolveMetaData excluded = args.exclude
            excludeRules << new DefaultExcludeRule(new ArtifactId(new ModuleId(excluded.id.group, excluded.id.name),
                    PatternMatcher.ANY_EXPRESSION,
                    PatternMatcher.ANY_EXPRESSION,
                    PatternMatcher.ANY_EXPRESSION),
                    ExactPatternMatcher.INSTANCE, null)
        }
        def dependencyMetaData = new LocalComponentDependencyMetaData(componentSelector, selector, "default", "default", [] as Set<IvyArtifactName>,
                                                                      excludeRules as org.apache.ivy.core.module.descriptor.ExcludeRule[], force, false, transitive)
        dependencyMetaData = new DslOriginDependencyMetaDataWrapper(dependencyMetaData, Stub(ModuleDependency))
        from.getDependencies().add(dependencyMetaData)
        return dependencyMetaData
    }

    def selectorResolvesTo(DependencyMetaData dependencyMetaData, ComponentIdentifier id, ModuleVersionIdentifier mvId) {
        1 * idResolver.resolve(dependencyMetaData, _) >> { DependencyMetaData dep, BuildableComponentIdResolveResult result ->
            result.resolved(id, mvId)
        }
    }

    def ids(ComponentResolveMetaData... descriptors) {
        return descriptors.collect { it.id } as Set
    }

    def modules(LenientConfiguration config) {
        Set<ModuleVersionIdentifier> result = new LinkedHashSet<ModuleVersionIdentifier>()
        List<ResolvedDependency> queue = []
        queue.addAll(config.getFirstLevelModuleDependencies({ true } as Spec))
        while (!queue.empty) {
            def node = queue.remove(0)
            result.add(node.module.id)
            queue.addAll(0, node.children)
        }
        return result
    }

    def artifacts(LenientConfiguration config) {
        return config.resolvedArtifacts.collect { it.moduleVersion.id } as Set
    }
}
