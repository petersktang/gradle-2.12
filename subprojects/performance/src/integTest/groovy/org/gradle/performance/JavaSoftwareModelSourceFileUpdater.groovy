/*
 * Copyright 2015 the original author or authors.
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

package org.gradle.performance

import org.apache.commons.io.FileUtils
import org.gradle.performance.fixture.BuildExperimentInvocationInfo
import org.gradle.performance.fixture.BuildExperimentListener
import org.gradle.performance.fixture.BuildExperimentListenerAdapter
import org.gradle.performance.fixture.BuildExperimentRunner
import org.gradle.performance.measure.MeasuredOperation

import java.util.regex.Pattern

class JavaSoftwareModelSourceFileUpdater extends BuildExperimentListenerAdapter {
    private File projectDir
    private List<File> projects
    private List<File> projectsWithDependencies
    private int projectCount
    private Map<Integer, List<Integer>> dependencies
    private Map<Integer, List<Integer>> reverseDependencies

    private final Set<File> updatedFiles = []
    private final int nonApiChanges
    private final int abiCompatibleChanges
    private final int abiBreakingChanges
    private final SourceUpdateCardinality cardinality

    JavaSoftwareModelSourceFileUpdater(int nonApiChanges, int abiCompatibleChanges, int abiBreakingChanges, SourceUpdateCardinality cardinality = SourceUpdateCardinality.ONE_FILE) {
        this.abiBreakingChanges = abiBreakingChanges
        this.nonApiChanges = nonApiChanges
        this.abiCompatibleChanges = abiCompatibleChanges
        this.cardinality = cardinality
    }

    private static int perc(int perc, int total) {
        (int) Math.ceil(total * (double) perc / 100d)
    }

    private int nonApiChangesCount() {
        perc(nonApiChanges, projectsWithDependencies.size())
    }

    private int abiCompatibleApiChangesCount() {
        perc(abiCompatibleChanges, projectsWithDependencies.size())
    }

    private int abiBreakingApiChangesCount() {
        perc(abiBreakingChanges, projectsWithDependencies.size())
    }

    private File backupFileFor(File file) {
        new File(file.parentFile, "${file.name}~")
    }

    private void createBackupFor(File file) {
        updatedFiles << file
        FileUtils.copyFile(file, backupFileFor(file), true)
    }

    private void restoreFiles() {
        updatedFiles.each { File file ->
            restoreFile(file)
        }
        updatedFiles.clear()
    }

    private void restoreFile(File file) {
        println "Restoring $file"
        def backup = backupFileFor(file)
        FileUtils.copyFile(backup, file, true)
        backup.delete()
    }

    @Override
    void beforeInvocation(BuildExperimentInvocationInfo invocationInfo) {
        projectDir = invocationInfo.projectDir
        if (projects == null) {

            projects = projectDir.listFiles().findAll { it.directory && it.name.startsWith('project') }.sort { it.name }
            projectCount = projects.size()

            // forcefully delete build directories (so that dirty local runs do not interfere with results)
            projects.each { pDir ->
                FileUtils.deleteDirectory(new File(pDir, 'build'))
            }

            // make sure execution is consistent independently of time
            Collections.shuffle(projects, new Random(31 * projectCount))
            // restore stale backup files in case a build was interrupted
            cleanup()

            // retrieve the dependencies in an exploitable form
            dependencies = new GroovyShell().evaluate(new File(projectDir, 'generated-deps.groovy'))
            reverseDependencies = [:].withDefault { [] }
            dependencies.each { p, deps ->
                deps.each {
                    reverseDependencies[it] << p
                }
            }
            projectsWithDependencies = projects.findAll { File it ->
                reverseDependencies[projectId(it)]
            }
        }
        if (!updatedFiles.isEmpty()) {
            restoreFiles()
        } else if (invocationInfo.phase != BuildExperimentRunner.Phase.WARMUP) {
            projectsWithDependencies.take(nonApiChangesCount()).each { subproject ->
                def internalDir = new File(subproject, 'src/main/java/org/gradle/test/performance/internal'.replace((char) '/', File.separatorChar))
                cardinality.onSourceFile(internalDir, '.java') { updatedFile ->
                    println "Updating non-API source file $updatedFile"
                    Set<Integer> dependents = affectedProjects(subproject)
                    createBackupFor(updatedFile)
                    updatedFile.text = updatedFile.text.replace('private final String property;', '''
private final String property;
public String addedProperty;
''')
                }
            }

            projectsWithDependencies.take(abiCompatibleApiChangesCount()).each { subproject ->
                def srcDir = new File(subproject, 'src/main/java/org/gradle/test/performance/'.replace((char) '/', File.separatorChar))
                cardinality.onSourceFile(srcDir, '.java') { updatedFile ->
                    println "Updating API source file $updatedFile in ABI compatible way"
                    Set<Integer> dependents = affectedProjects(subproject)
                    createBackupFor(updatedFile)
                    updatedFile.text = updatedFile.text.replace('return property;', 'return property.toUpperCase();')
                }
            }

            projectsWithDependencies.take(abiBreakingApiChangesCount()).each { subproject ->
                def srcDir = new File(subproject, 'src/main/java/org/gradle/test/performance/'.replace((char) '/', File.separatorChar))
                cardinality.onSourceFile(srcDir, '.java') { updatedFile ->
                    println "Updating API source file $updatedFile in ABI breaking way"
                    createBackupFor(updatedFile)
                    updatedFile.text = updatedFile.text.replace('one() {', 'two() {')
                    // need to locate all affected classes
                    def updatedClass = updatedFile.name - '.java'
                    affectedProjects(subproject).each {
                        def subDir = new File(projectDir, "project$it")
                        if (subDir.exists()) {
                            // need to check for existence because dependency
                            // generation strategy may be generating dependencies
                            // outside what is really declared
                            subDir.eachFileRecurse { f ->
                                if (f.name.endsWith('.java')) {
                                    def txt = f.text
                                    if (txt.contains("${updatedClass}.one()")) {
                                        createBackupFor(f)
                                        println "Updating consuming source $f"
                                        f.text = txt.replaceAll(Pattern.quote("${updatedClass}.one()"), "${updatedClass}.two()")
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    @Override
    void afterInvocation(BuildExperimentInvocationInfo invocationInfo, MeasuredOperation operation, BuildExperimentListener.MeasurementCallback measurementCallback) {
        if (invocationInfo.iterationNumber == invocationInfo.iterationMax) {
            println "Last iteration complete"
            cleanup()
        }
    }

    void cleanup() {
        projectDir?.eachFileRecurse { file ->
            if (file.name.endsWith('~')) {
                restoreFile(new File(file.parentFile, file.name - '~'))
            }
        }
        updatedFiles.clear()
    }

    private Set<Integer> affectedProjects(File subproject) {
        Set dependents = reverseDependencies[projectId(subproject)] as Set
        Set transitiveClosure = new HashSet<>(dependents)
        int size = -1
        while (size != transitiveClosure.size()) {
            size = transitiveClosure.size()
            def newDeps = []
            transitiveClosure.each {
                newDeps.addAll(reverseDependencies[it])
            }
            transitiveClosure.addAll(newDeps)
        }
        println "Changes will transitively affect projects ${transitiveClosure.join(' ')}"
        dependents
    }

    private int projectId(File pDir) {
        Integer.valueOf(pDir.name - 'project')
    }
}
