// ===================================
// = logparser for Jenkins pipelines =
// ===================================
// a library to parse and filter logs

// *******************
// * SCRIPT VARIABLE *
// *******************
@groovy.transform.Field
def verbose = false

@NonCPS
def setVerbose(v) {
    verbose = v
}

// TODO: param runWrapper

//***************
//* LOG PARSING *
//***************

// return list of maps describing the logs offsets and workflow ids
// [ { id: id, start: start, stop: stop }* ]
// id can be null (technical part of the logs)
// cf https://stackoverflow.com/a/57351397
@NonCPS
List<java.util.LinkedHashMap> getLogIndex() {

    // return value
    def logIndex = []

    // read log-index file
    // (no stream to avoid infinite loop while parsing it: it shall grow as long as logs are printed)
    def jobRoot = currentBuild.rawBuild.getRootDir()
    def logIndexFile = new File(jobRoot, 'log-index')
    assert logIndexFile.exists()

    // format of a line : <start offset> [<id>]
    // if no id it's a block without id (technical pipeline log)

    def previousStart = 0
    def previousId = null
    for (line in logIndexFile.text.split('\n')) {
        def logIndexItems = line.split(' ')
        assert logIndexItems.size() == 1 || logIndexItems.size() == 2, 'failed to parse log-index file'
        def start = Integer.parseInt(logIndexItems[0])
        def id = null
        if (logIndexItems.size() == 2) {
            id = Integer.parseInt(logIndexItems[1])
        }
        assert start > previousStart, 'failed to parse log-index file'

        logIndex += [ [ id: previousId, start: previousStart, stop: start ] ]
        previousStart = start
        previousId = id
    }

    if (verbose) {
        //print "logIndexFile=${logIndexFile.text}"
        print "logIndex=${logIndex}"
    }

    return logIndex
}

// return map describing the content of each branch:
// - branch name (if any otherwise null)
// - workflow ids of children
// - nested branches ids
// - parent branch id
// - branch name of all parents (parent, grandParent, etc ...)
// { id: { name: brancheName, children: [id ...], nested: [id, ...], parent: id, parentNames: [ parentName, grandParentName, ...] } }
// cf https://stackoverflow.com/a/57351397
// TODO : try using directly currentBuild.rawBuild.allActions.findAll { it.class == org.jenkinsci.plugins.workflow.job.views.FlowGraphAction } which should contain the same information (and avoid parsing xml files on disk)
@NonCPS
java.util.LinkedHashMap getWorkflowBranchMap() {

    // return value
    def branchMap = [:]

    def jobRoot = currentBuild.rawBuild.getRootDir()

    // parse workflow/*.xml to get workflow ids and parents

    def workflow = new File(jobRoot, 'workflow')
    assert workflow.exists()
    assert workflow.isDirectory()
    def fileList = workflow.listFiles()
    // sort by name, name must be <number>.xml
    fileList.each { assert it.name ==~ /^[0-9]*\.xml$/ }
    fileList = fileList.sort { a,b -> Integer.parseInt(a.name.replace('.xml','')) <=> Integer.parseInt(b.name.replace('.xml','')) }

    // temporary map of parent branches
    def parentBranchMap = [:]

    for (file in fileList) {
        def rootnode = new XmlSlurper().parse(file.path)
        def parents = rootnode.node.parentIds.string.collect{ Integer.parseInt("$it") }
        def id = Integer.parseInt("${rootnode.node.id}")
        def nodeClass = rootnode.node.@'class'

        //if (verbose) {
        //    print "file ${file.path}: class=${nodeClass} id=${id} parents=${parents}"
        //}

        // start of branch: record branch name
        if (nodeClass == 'cps.n.StepStartNode') {
            def descriptorId = rootnode.node.descriptorId
            def name = null
            def stage = false
            if (descriptorId == 'org.jenkinsci.plugins.workflow.cps.steps.ParallelStep') {
                name = rootnode.actions.'org.jenkinsci.plugins.workflow.cps.steps.ParallelStepExecution_-ParallelLabelAction'.branchName.toString()
            } else if (descriptorId == 'org.jenkinsci.plugins.workflow.support.steps.StageStep') {
                name = rootnode.actions.'wf.a.LabelAction'.displayName
                if (name != '') {
                    stage = true
                }
            }
            if (name == '') {
                name = null
            }

            branchMap."$id" = [:]
            branchMap."$id".children = []
            branchMap."$id".nested = []
            branchMap."$id".name = name
            branchMap."$id".stage = stage

            //if (verbose) {
            //    print "file ${file.path}: branchMap.$id=${branchMap."$id"}"
            //}
        }

        if (nodeClass == 'org.jenkinsci.plugins.workflow.graph.FlowStartNode') {
            assert parents.size() == 0
            // no parent, start family tree
            branchMap."$id" = [:]
            branchMap."$id".children = []
            branchMap."$id".nested = []
            branchMap."$id".name = null
            branchMap."$id".parent = null
        }
        else {
            assert parents.size() > 0
            def branchParentId

            if (nodeClass == 'cps.n.StepEndNode') {
                // end of branch
                branchParentId = Integer.parseInt("${rootnode.node.startId}")
            }
            else {
                // not a end of branch so one parent only
                assert parents.size() == 1
                branchParentId = parents[0]
            }

            // if the parent is not a branch but the child of a branch
            // use that branch id instead: it is the true branch parent
            if (
                ! ( branchParentId in branchMap.keySet().collect{ Integer.parseInt("$it") } ) &&
                ( branchParentId in parentBranchMap.keySet().collect{ Integer.parseInt("$it") } )
            ) {
                branchParentId = parentBranchMap."$branchParentId"
            }

            parentBranchMap."$id" = branchParentId
            if ( branchParentId in branchMap.keySet().collect{ Integer.parseInt("$it") } ) {
                if ( id in branchMap.keySet().collect{ Integer.parseInt("$it") } ) {
                    branchMap."$branchParentId".nested += [ id ]
                    branchMap."$id".parent = branchParentId
                } else {
                    branchMap."$branchParentId".children += [ id ]
                }
            }

            //if (verbose) {
            //    print "file ${file.path}: branchParentId=${branchParentId}"
            //}
        }
    }

    // fill in list of parent branches (next parent first)
    branchMap.each { k, v ->
        v.parentNames = []
        def next = v.parent
        while (next) {
            if (branchMap."${next}".name) {
                v.parentNames += [ branchMap."${next}".name ]
            }
            next = branchMap."${next}".parent
        }
    }

    if (verbose) {
        print "parentBranchMap=${parentBranchMap}"
        print "branchMap=${branchMap}"
    }

    return branchMap
}


// return list of maps describing the logs offsets, workflow ids and branche name(s)
// [ { id: id, start: start, stop: stop, branches: [ branch1, branch2, ... ] }* ]
// id and branches can be null. branches contain all nested branch (starting with the nested one)
@NonCPS
List<java.util.LinkedHashMap> getLogIndexWithBranches() {

    // 1/ read log-index file with log offsets first
    // (read it before to parse id files to have only known ids)
    def logIndex = getLogIndex()

    // 2/ get branch information
    def branchMap = getWorkflowBranchMap()

    // and use branchMap to fill reverse map for all ids : for each id find which branch(es) it belong to
    def idBranchMap = [:]
    branchMap.each { k, v ->
        v.children.each {
            // each id should appear as child of one branch only
            assert idBranchMap."$it" == null
            if (v.name) {
                idBranchMap."$it" = [v.name] + v.parentNames
            } else {
                idBranchMap."$it" = v.parentNames
            }
        }
        if (v.name) {
            idBranchMap."$k" = [v.name] + v.parentNames
        } else {
            idBranchMap."$k" = v.parentNames
        }
    }

    if (verbose) {
        print "idBranchMap=${idBranchMap}"
    }

    // finally fill the logIndex with list of branches
    def logIndexWithBranches = logIndex.collect {
        if (it.id) {
            assert idBranchMap."${it.id}" != null
            return [ id: it.id, start: it.start, stop: it.stop, branches: idBranchMap."${it.id}" ]
        } else {
            return [ id: null, start: it.start, stop: it.stop, branches: [] ]
        }
    }

    if (verbose) {
        print "logIndexWithBranches=${logIndexWithBranches}"
    }

    return logIndexWithBranches
}

//*******************************
//* GENERATE URL TO BRANCH LOGS *
//*******************************
// return them as map (key = id in case 2 branches have the same name)
// TODO: find a better representation (tree)

java.util.LinkedHashMap getBlueOceanLogMap() {
    // get branch information
    def branchMap = getWorkflowBranchMap()

    // remove nameless
    return branchMap.findAll{ k,v -> v.name != null }.collect{ k,v ->
        def blueOceanUrl = "${env.HUDSON_URL}blue/organizations/jenkins/${env.JOB_NAME}/detail/${env.JOB_NAME}/${env.BUILD_NUMBER}/pipeline/${k}"
        def blueOceanRestUrl = "${env.HUDSON_URL}blue/rest/organizations/jenkins/pipelines/${env.JOB_NAME}/runs/${env.BUILD_NUMBER}/nodes/${k}/"
        def blueOceanLog = "${blueOceanRestUrl}log/?start=0"

        return [
            (k): [
                name: v.name,
                parentNames: v.parentNames,
                stage: v.stage,
                url: blueOceanUrl,
                restUrl: blueOceanRestUrl,
                log: blueOceanLog
            ]
        ]
    }
}

java.util.LinkedHashMap getPipelineStepsLogMap() {
    // get branch information
    def branchMap = getWorkflowBranchMap()

    return branchMap.collect{ k,v ->
        return [
            (k): [
                name: v.name,
                parentNames: v.parentNames,
                stage: v.stage,
                url: "${env.HUDSON_URL}job/${env.JOB_NAME}/${env.BUILD_NUMBER}/execution/node/${k}/",
                children: v.children.collect{
                    def childURL = "${env.HUDSON_URL}job/${env.JOB_NAME}/${env.BUILD_NUMBER}/execution/node/${it}/"
                    return [
                        (it): [
                            url: childURL,
                            log: "${childURL}log"
                        ]
                    ]
                },
                // keep info to traverse the tree
                nested: v.nested,
                parent: v.parent
            ]
        ]
    }
}

//***************************
//* LOG FILTERING & EDITING *
//***************************

// utility function to find which version of the logs is currently available
@NonCPS
Boolean logHasNewFormat()
{
    def WJpluginVerList = Jenkins.instance.pluginManager.plugins.findAll{ it.getShortName() == 'workflow-job' }.collect { it.getVersion() }
    assert WJpluginVerList.size() == 1, 'could not fing workflow-job plugin version'

    def WJpluginVer = WJpluginVerList[0].split(/\./)

    if (WJpluginVer.size() > 1 && WJpluginVer[0] ==~ /\d+/) {
        def major = WJpluginVer[0].toInteger()

        def minor = null
        if (WJpluginVer.size() > 2 && WJpluginVer[1] ==~ /\d+/) {
            minor = WJpluginVer[1].toInteger()
        } else if (WJpluginVer.size() == 1) {
            minor = 0
        }
        if (minor != null) {
            return ((major > 2) || (major == 2 && minor > 25))
        }
    }

    if (verbose) {
        print "failed to parse ${WJpluginVerList[0]}"
    }

    // failed to find version ...
    // try to guess some other way: log-index did not exist before
    def jobRoot = currentBuild.rawBuild.getRootDir()
    def logIndexFile = new File(jobRoot, 'log-index')
    return logIndexFile.exists()
}

// return log file with BranchInformation
// - return logs only for one branch if filterBranchName not null (default null)
// - with parent information for nested branches if options.showParents is true (default)
//   example:
//      if true: "[branch2] [branch21] log from branch21 nested in branch2"
//      if false "[branch21] log from branch21 nested in branch2"
// - with a marker showing nested branches if options.markNestedFiltered is true (default) and if filterBranchName is not null
//   example:
//      "[ filtered 6787 bytes of logs for nested branches: branch2.branch21 branch2.branch22 ] (...)"
// - without vt100 markups if options.hideVT100 is true (default)
// - without Pipeline technical logs if options.hidePipeline is true (default)
//
// cf https://stackoverflow.com/questions/38304403/jenkins-pipeline-how-to-get-logs-from-parallel-builds
// cf https://stackoverflow.com/a/57351397
// (workaround for https://issues.jenkins-ci.org/browse/JENKINS-54304)
@NonCPS
String getLogsWithBranchInfo(java.util.LinkedHashMap options = [:])
{
    // return value
    def output = ''

    // 1/ parse options
    def defaultOptions = [ filter: [], showParents: true, markNestedFiltered: true, hidePipeline: true, hideVT100: true ]
    // merge 2 maps with priority to options values
    options = defaultOptions.plus(options)
    options.keySet().each{ assert it in ['filter', 'showParents', 'markNestedFiltered', 'hidePipeline', 'hideVT100'], "invalid option $it" }
    // make sure there is no type mismatch when comparing elements of options.filter
    options.filter = options.filter.collect{ it != null ? it.toString() : null }

    def removeTechnicalLogs = { buffer ->
        if (options.hideVT100 || options.hidePipeline) {
            // remove Pipeline logs or VT100 markups ( ESC[8m.*ESC[0m which make logfile hard to read )
            // cf http://ascii-table.com/ansi-escape-sequences-vt-100.php
            // cf https://www.codesd.com/item/how-to-delete-jenkins-console-log-annotations.html
            // cf https://issues.jenkins-ci.org/browse/JENKINS-48344
            def VT100Pattern = '\\x1B\\[8m.*?\\x1B\\[0m'
            def PipelinePattern = "(?m)^${VT100Pattern}\\[Pipeline\\] .*\$\\n|(?m)^\\[Pipeline\\] .*\$\\n"

            def pattern = (options.hideVT100 && options.hidePipeline) ?
                PipelinePattern + '|' + VT100Pattern :
                (options.hideVT100 ? VT100Pattern : PipelinePattern)
            return buffer.replaceAll(/${pattern}/, '')
        } else {
            return buffer
        }
    }

    if (logHasNewFormat()) {
        // get the log index before to read the logfile to make sure all items are in the file
        def logIndex = getLogIndexWithBranches()

        // Read the log file as byte[].
        def jobRoot = currentBuild.rawBuild.getRootDir()
        def logFile = new File("${jobRoot}/log")
        assert logFile.exists()
        def logStream = currentBuild.rawBuild.getLogInputStream()

        def sizeFiltered = 0
        def filteredBranches = [:]

        def filterMsg = { bytesFiltered, filteredBranchesMap ->
            def msg = ''
            if (options.markNestedFiltered) {
                // highlight nested branches filtered when filterBranchName is not null
                // technically they are a sub-part of the branch we are filtering
                // but showing them might show logs from mutiple branches: better to filter them 1 by 1 (caller decision)
                // put a marker in log to indicate that logs for those branches were filtered
                // "[ filtered 6787 bytes of logs for nested branches: branch2.branch21 branch2.branch22 ] (...)"
                // TODO: number of filtered lines rather than number of bytes
                msg = "[ filtered ${bytesFiltered} bytes of logs"
                if (filteredBranchesMap.size() != 0) {
                    msg += " for nested branches: ${ filteredBranchesMap.keySet().sort().join(' ') }"
                }
                msg +=  " ] (...)\n"
            }
            return msg
        }

        def keepBranch = { branchName ->
            // check if branch was in the list of branches to keep
            return options.filter.count{ it != null && branchName ==~ /^${it}$/ } > 0
        }

        logIndex.each {
            def logSize = it.stop - it.start
            if (it.branches.size() > 0) {
                // in a branch

                def branchInfo = ''
                if (options.showParents) {
                    // reverse list to show parent branch first
                    branchInfo = it.branches.reverse().collect{ "[$it] " }.sum()
                } else {
                    branchInfo = "[${it.branches[0]}] "
                }

                if (
                    options.filter.size() == 0 ||
                    keepBranch(it.branches[0])
                ) {
                    // configured to show this branch

                    // if some branches were filtered add nested marker first
                    if (sizeFiltered > 0) {
                        output += filterMsg(sizeFiltered, filteredBranches)
                        sizeFiltered = 0
                        filteredBranches = [:]
                    }

                    byte[] logs = new byte[logSize]
                    assert logStream.read(logs) == logSize

                    // offset relative to the end of the log
                    // to skip trailing \n when adding prefix
                    def offset = 0
                    if (new String(logs, logSize - 1, 1, "UTF-8") == '\n') {
                        offset = -1
                    }
                    // split with -1 to avoid removing empty lines at the end (if string endsWith(\n\n))
                    def str = new String(logs, 0, logSize + offset, "UTF-8").split('\n', -1).collect{ "${branchInfo}${it}" }.join('\n')
                    if (offset == -1) {
                        // put back trailing \n
                        str += '\n'
                    }

                    str = removeTechnicalLogs(str)
                    output += str
                } else if (
                     it.branches.count{ keepBranch(it) } > 0 ||
                     null in options.filter
                ) {
                    // branch is not kept (not in filter) but one of its parent branch is kept: record it as filtered

                    assert logStream.skip(logSize)
                    sizeFiltered += logSize
                    filteredBranches."${it.branches.reverse().join('.')}" = true
                } else {
                    // none of the parent branches is kept, skip this one entirely

                    assert logStream.skip(logSize)
                }
            } else if (
                (options.filter.size() == 0) ||
                (null in options.filter)
            ) {
                // not in a branch and configured to show main branch

                byte[] logs = new byte[logSize]
                assert logStream.read(logs) == logSize
                def str = new String(logs, 0, logSize, "UTF-8")

                str = removeTechnicalLogs(str)

                if (str.size() > 0) {
                    // if branches were filtered add nested marker first
                    if (sizeFiltered > 0) {
                        output += filterMsg(sizeFiltered, filteredBranches)
                        sizeFiltered = 0
                        filteredBranches = [:]
                    }
                    output += str
                }
            } else {
                // not in a branch and configured to show a branch (and NOT main branch 'null')

                assert logStream.skip(logSize)
            }
        }

        if (sizeFiltered > 0) {
            output += filterMsg(sizeFiltered, filteredBranches)
        }

    } else {
        // pre log-index version
        // parse logs the old way with a regexp since branches are already set
        // options showParents & markNestedFiltered not implemented (ignored)

        output = removeTechnicalLogs(currentBuild.rawBuild.log)

        if (options.filter.size() != 0) {
            // get all lines starting with [BranchName]
            def expr = []
            options.filter.each {
                if (it == null) {
                    expr += [ '(?m)^$|(?m)^[^\\[\\n].*$' ]
                } else {
                    expr += [ "(?m)^\\[${it}\\] .*\$" ]
                }
            }
            output = (output =~ /${expr.join('|')}/ ).collect{ "${it}\n" }.join('')
        }
    }

    return output
}

//*************
//* ARCHIVING *
//*************

// archive buffer directly on the master (no need to instantiate a node like ArchiveArtifact)
// cf https://github.com/gdemengin/pipeline-whitelist
@NonCPS
void archiveArtifactBuffer(String name, String buffer) {
    def jobRoot = currentBuild.rawBuild.getRootDir()
    def file = new File("${jobRoot}/archive/${name}")

    if (verbose) {
        print "archiving ${file.path}"
    }

    if (! file.parentFile.exists()){
        file.parentFile.mkdirs();
    }
    file.write(buffer)
}

// archive logs with [<branch>] prefix on lines belonging to <branch>
// and filter by branch if filterBranchName not null
// cf https://stackoverflow.com/questions/38304403/jenkins-pipeline-how-to-get-logs-from-parallel-builds
// cf https://stackoverflow.com/a/57351397
// (workaround for https://issues.jenkins-ci.org/browse/JENKINS-54304)
@NonCPS
void archiveLogsWithBranchInfo(String name, java.util.LinkedHashMap options = [:])
{
    archiveArtifactBuffer(name, getLogsWithBranchInfo(options))
}


return this
