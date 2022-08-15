#!/usr/bin/env groovy
import hudson.model.*
import hudson.EnvVars
import java.net.URL
import groovy.xml.*

String executionEnvironment
String synchConfigFile         
String branchMappingString     
String ispwTargetLevel
String ispwSourceLevel
String gitSourceBranch
String ccDdioOverrides     
String sonarCobolFolder        
String sonarCopybookFolder     
String sonarResultsFileVt
String sonarResultsFileNvt
String sonarResultsFileList     
String sonarCodeCoverageFile   
String jUnitResultsFile
String applicationQualifier

def pipelineParms
def ispwConfig
def synchConfig

def cocoParms
def executionBehavior

def call(Map execParms){

    //**********************************************************************
    // Start of Script 
    //**********************************************************************

    stage ('Initialize') {

        // If other than 1st build, the code will run in a parallel node and will have to unstash the workspace
        if(!(BUILD_NUMBER == "1")) {

            dir('./') {
                deleteDir()
            }

            unstash name: 'workspace'

        }
        
        initialize(execParms)
    }

    stage('Load code to mainframe') {

        if(executionBehavior.mainframeChanges) {

            echo "[Info] - Loading code to mainframe level " + ispwTargetLevel + "."

            runMainframeLoad()

        }
        else {

            echo executionBehavior.skipReason + "\n[Info] - No code will be loaded to the mainframe."

        }
    }

    checkForBuildParams(synchConfig.ispw.automaticBuildFile)

    stage('Build mainframe code') {

        if(executionBehavior.mainframeChanges){

            echo "[Info] - Determining Generate Parms for Components."

            prepMainframeBuild()

            echo "[Info] - Building code at mainframe level " + ispwTargetLevel + "."

            runMainframeBuild()

        }
        else{

            echo executionBehavior.skipReason + "\n[Info] - Skipping Mainframe Build."

        }
    }

    if(executionBehavior.executeVt){

        stage('Execute Unit Tests') {           

            runUnitTests()

        }
    }

    if(executionBehavior.executeNvt){

        stage('Execute Module Integration Tests') {

            runIntegrationTests()

        }
    }

    if(executionBehavior.mainframeChanges){

        getCocoResults()

    }
        
    stage("SonarQube Scan") {

        runSonarScan()

    }   

    if(executionBehavior.executeXlr){

        stage("Trigger Release - View Console Log for Instructions") {

            triggerXlRelease()
        
        }
    }
}

def initialize(execParms){

    synchConfigFile             = './git2ispw/synchronization.yml'
    pipelineParms               = execParms
    cocoParms                   = [:]
    branchMappingString         = ''
    ispwTargetLevel             = ''    
    ccDdioOverrides             = ''

    //*********************************************************************************
    // Read synchconfig.yml from Shared Library resources folder
    //*********************************************************************************
    def fileText                = libraryResource synchConfigFile
    tmpConfig                   = readYaml(text: fileText)

    // Determine which execution environment/configuration to use. If none is specified, "cwcc" is the default
    if(pipelineParms.executionEnvironment == null){

        pipelineParms.executionEnvironment = 'cwcc'

    }
    else{
        pipelineParms.executionEnvironment = execParms.executionEnvironment.toLowerCase()
    }

    synchConfig                 = tmpConfig.executionEnvironments[pipelineParms.executionEnvironment]

    //*********************************************************************************
    // Build paths to subfolders of the project root
    //*********************************************************************************

    ispwConfigFile              = synchConfig.ispw.configFile.folder    + '/' + synchConfig.ispw.configFile.name

    tttRootFolder               = synchConfig.ispw.mfProject.rootFolder + '/' + synchConfig.ttt.folders.root
    tttVtFolder                 = tttRootFolder                         + '/' + synchConfig.ttt.folders.virtualizedTests
    tttNvtFolder                = tttRootFolder                         + '/' + synchConfig.ttt.folders.nonVirtualizedTests

    ccSources                   = synchConfig.ispw.mfProject.rootFolder + synchConfig.ispw.mfProject.sourcesFolder

    sonarCobolFolder            = synchConfig.ispw.mfProject.rootFolder + synchConfig.ispw.mfProject.sourcesFolder
    sonarCopybookFolder         = synchConfig.ispw.mfProject.rootFolder + synchConfig.ispw.mfProject.sourcesFolder
    sonarResultsFolder          = synchConfig.ttt.results.sonar.folder
    sonarResultsFileVt          = synchConfig.ttt.folders.virtualizedTests      + '.' + synchConfig.ttt.results.sonar.fileNameBase
    sonarResultsFileNvt         = synchConfig.ttt.folders.nonVirtualizedTests   + '.' + synchConfig.ttt.results.sonar.fileNameBase
    sonarResultsFileList        = []        
    sonarCodeCoverageFile       = synchConfig.coco.results.sonar.folder + '/' + synchConfig.coco.results.sonar.file
    
    jUnitResultsFile            = synchConfig.ttt.results.jUnit.folder  + '/' + synchConfig.ttt.results.jUnit.file

    //*********************************************************************************
    // Read ispwconfig.yml
    // Strip the first line of ispwconfig.yml because readYaml can't handle the !! tag
    //*********************************************************************************
    def tmpText                 = readFile(file: ispwConfigFile)

    // remove the first line (i.e. use the the substring following the first carriage return '\n')
    tmpText                     = tmpText.substring(tmpText.indexOf('\n') + 1)

    // convert the text to yaml
    ispwConfig                  = readYaml(text: tmpText)

    executionBehavior           = determinePipelineBehavior(BRANCH_NAME, BUILD_NUMBER)

    branchMappingString         = buildBranchMappingString(synchConfig.ispw.branchInfo)

    gitSourceBranch             = getGitSourceBranch(BRANCH_NAME)

    if(gitSourceBranch == null) {
        error "[Error] - The source branch for this build could not be determined. Pipeline will be aborted"
    }

    echo "[Info] - Determined source branch " + gitSourceBranch

    ispwTargetLevel             = getIspwLevelFromBranchName(BRANCH_NAME, synchConfig.ispw.branchInfo)

    //*********************************************************************************
    // If target level is empty the branch name could not be mapped
    //*********************************************************************************
    if(ispwTargetLevel == ''){

        error "[Error] - No branch mapping for target branch ${BRANCH_NAME} was found. Execution will be aborted.\n" +
            "Correct the branch name to reflect naming conventions."
    }

    echo "[Info] - Determined ISPW target level " + ispwTargetLevel

//    if (gitSourceBranch.contains('feature') | gitSourceBranch.contains('bugfix')) {
    if (gitSourceBranch.contains('feature')) {    
        
        ispwSourceLevel         = getIspwLevelFromSettings(synchConfig.ispw.settingsFile.folder + '/' + synchConfig.ispw.settingsFile.name)

    }
    else {
        ispwSourceLevel         = getIspwLevelfromBranchName(gitSourceBranch, synchConfig.ispw.branchInfo)        
    }

    //*********************************************************************************
    // If source level is empty the branch name could not be mapped
    //*********************************************************************************
    if(ispwSourceLevel == ''){

        error "[Error] - No branch mapping for source branch ${gitSourceBranch} was found. Execution will be aborted."
    }

    echo "[Info] - Determined ISPW source level " + ispwSourceLevel

    applicationQualifier        = synchConfig.ispw.libraryQualifier + ispwConfig.ispwApplication.application

    cocoParms.systemId          = ispwTargetLevel
    cocoParms.testId            = BUILD_NUMBER

}

/* Determine execution type of the pipeline */
/* If it executes for the first time, i.e. the branch has just been created, only scan sources and don't execute any tests      */
/* Else, depending on the branch type or branch name (feature, development, fix or main) determine the type of tests to execute */
/* If main branch and mainfgrame changes have been applied, trigger XLR */
def determinePipelineBehavior(branchName, buildNumber){

    def behavior = [
            mainframeChanges: true,
            executeVt:        true,
            executeNvt:       true,
            executeXlr:       true,
            skipReason:       ""    
        ]

    if (buildNumber == "1") {

        behavior.mainframeChanges  = false
        behavior.executeVt         = false
        behavior.executeNvt        = false
        behavior.executeXlr        = false
        behavior.skipReason        = "[Info] - First build for branch '${branchName}'. Only sources will be scanned by SonarQube"
    }    
    else if (BRANCH_NAME.contains("feature")) {

        behavior.mainframeChanges  = true
        behavior.executeVt         = true
        behavior.executeNvt        = false
        behavior.executeXlr        = false
        behavior.skipReason        = "[Info] - '${branchName}' is a feature branch."
    }
    else if (BRANCH_NAME.contains("bugfix")) {

        behavior.mainframeChanges  = true
        behavior.executeVt         = true
        behavior.executeNvt        = false
        behavior.executeXlr        = false
        behavior.skipReason        = "[Info] - Branch '${branchName}'."
    }
    else if (BRANCH_NAME.contains("development")) {

        behavior.mainframeChanges  = true
        behavior.executeVt         = true
        behavior.executeNvt        = true
        behavior.executeXlr        = false
        behavior.skipReason        = "[Info] - Branch '${branchName}'."
    }
    else if (BRANCH_NAME.contains("main")) {

        behavior.mainframeChanges  = true
        behavior.executeVt         = false
        behavior.executeNvt        = true
        behavior.executeXlr        = true
        behavior.skipReason        = "[Info] - Branch '${branchName}'."
    }

    return behavior
}

//*********************************************************************************
// Build branch mapping string to be used as parameter in the gitToIspwIntegration
//*********************************************************************************
def buildBranchMappingString(branchInfo){

    def mappingString = ''

    for (info in branchInfo) {

        mappingString = mappingString + info.key + '** => ' + info.value.ispwLevel + ',' + info.value.mapRule + '\n'

    }

    return mappingString
}

def getGitSourceBranch(targetBranch) {

    def numberCommits   = 0
    def sourceBranch    = ''

    if(targetBranch == "main"){
        numberCommits = 2
    }
    else if (targetBranch == "development") {
        numberCommits = 2
    }

    if (numberCommits > 0) {
        
        def stdout          = bat(returnStdout: true, script: 'git log -' + numberCommits.toString() + ' --right-only --oneline')
        def commits         = stdout.split("\n")

for (comm in commits){
    echo comm
}

        def sourceCommitId  = commits[numberCommits].split(" ")

        stdout              = bat(returnStdout: true, script: 'git branch -a --contains ' + sourceCommitId)

echo stdout
        
        def branchInfo      = sourceCommit.substring(sourceCommit.indexOf("(") + 1,sourceCommit.indexOf(")"))
        def branchList      = branchInfo.split(" ")
        
        for (branch in branchList) {
            if (branch.indexOf("origin") >= 0) {
                sourceBranch = branch.replace(",", "").replace("origin/", "")
                break
            }
        }
    }
    else {
        sourceBranch = targetBranch
    }

    return sourceBranch
}

//*********************************************************************************
// Get ISPW level from Git branch name
//*********************************************************************************
def getIspwLevelFromBranchName(branchName, branchInfo){

    def ispwLevel = ''

    branchInfo.each {

        /* Get ISPW Level from branch Mapping info for Git branch */
        if(branchName.contains(it.key)) {

            ispwLevel     = it.value.ispwLevel
            
        }
    }

    return ispwLevel
}


def getIspwLevelFromSettings(fileName) {

    def ispwLevel           = ''
    def projectProperties   = readFile(file: fileName).split("\n")

    for (property in projectProperties) {

        setting = property.split("=")

        if (setting[0] == "ispwMappingLevel") {

            ispwLevel = setting[1].trim()

        }
    }

    return ispwLevel
}

def runMainframeLoad() {

    gitToIspwIntegration( 
        connectionId:       synchConfig.environment.hci.connectionId,                    
        credentialsId:      pipelineParms.hostCredentialsId,                     
        runtimeConfig:      ispwConfig.ispwApplication.runtimeConfig,
        stream:             ispwConfig.ispwApplication.stream,
        app:                ispwConfig.ispwApplication.application, 
        branchMapping:      branchMappingString,
        ispwConfigPath:     ispwConfigFile, 
        gitCredentialsId:   pipelineParms.gitCredentialsId, 
        gitRepoUrl:         pipelineParms.gitRepoUrl
    )

}

// If the automaticBuildParams.txt has not been created, it means no programs
// have been changed and the pipeline was triggered for other changes (e.g. in configuration files)
// These changes do not need to be "built".
def checkForBuildParams(automaticBuildFile){

    try {
        def automaticBuildInfo = readJSON(file: automaticBuildFile)
    }
    catch(Exception e) {

        echo "[Info] - No Automatic Build Params file was found.  Meaning, no mainframe sources have been changed.\n" +
        "[Info] - Mainframe Build and Test steps will be skipped. Sonar scan will be executed against code only."

        executionBehavior.mainframeChanges = false
        executionBehavior.executeVt        = false
        executionBehavior.executeNvt       = false
        executionBehavior.executeXlr       = false        
        executionBehavior.skipReason       = executionBehavior.skipReason + "\n[Info] - No changes to mainframe code."

    }
}

/* Determine Generate Parms for the different Tasks */
def prepMainframeBuild(){

    def cesToken                = getCesToken(pipelineParms.cesCredentialsId)

    /* Read list of task IDs after loading modified sources to ISPW */
    def automaticBuildInfo      = readJSON(file: synchConfig.ispw.automaticBuildFile)
    def currentAssignmentId     = automaticBuildInfo.containerId 
    def taskIds                 = automaticBuildInfo.taskIds
    
    automaticBuildInfo.taskIds  = []

    for(taskId in taskIds) {
        
        def taskGenInfo             = [:]
        def taskPath                = ''
        def taskSourceAssignmentId  = ''

        def taskInfo                = getCurrentTaskInfo(currentAssignmentId, taskId, cesToken)

        def componentVersions       = getComponentVersions(ispwConfig.ispwApplication.application, taskInfo.moduleName, taskInfo.moduleType, cesToken)

        /* Determine the Assignment ID of the source ISPW level */
        for(version in componentVersions) {
            if(version.level == ispwSourceLevel){
                taskSourceAssignmentId = version.assignmentId                
            }
        }

        def taskList                = getTasksForAssignment(taskSourceAssignmentId, cesToken)

        /* Build generate parms based on parms from source level */
        taskGenInfo.stream          = ispwConfig.ispwApplication.stream
        taskGenInfo.application     = ispwConfig.ispwApplication.application
        taskGenInfo.moduleName      = taskInfo.moduleName
        taskGenInfo.moduleType      = taskInfo.moduleType
        taskGenInfo.currentLevel    = ispwTargetLevel
        taskGenInfo.startingLevel   = ispwSourceLevel

        def taskSourceInfo          = getTaskSourceInfo(taskList, taskGenInfo)

        /* Set info based on the task determined before */
        taskGenInfo.cics            = taskSourceInfo.cics
        taskGenInfo.sql             = taskSourceInfo.sql
        taskGenInfo.ims             = taskSourceInfo.ims
        taskGenInfo.option1         = taskSourceInfo.option1
        taskGenInfo.option2         = taskSourceInfo.option2
        taskGenInfo.option3         = taskSourceInfo.option3
        taskGenInfo.option4         = taskSourceInfo.option4
        taskGenInfo.option5         = taskSourceInfo.option5
        taskGenInfo.program         = taskSourceInfo.program

        def newTaskId               = setNewTaskInfo(currentAssignmentId, taskGenInfo, cesToken)

        automaticBuildInfo.taskIds.add(newTaskId)
    }

    writeJSON(file: synchConfig.ispw.automaticBuildFile, json: automaticBuildInfo)

    error "Preliminary Stop"
}

def getCesToken(credentialsId) {

    def token

    withCredentials(
        [
            string(
                credentialsId: credentialsId, 
                variable: 'cesTmp'
            )
        ]
    ) {
        
        token    = cesTmp

    }

    return token
}

/* Get current task info from ISPW target level */
def getCurrentTaskInfo(currentAssignmentId, taskId, cesToken) {

    def response = httpRequest(
        url:                    synchConfig.environment.ces.url + "/ispw/ispw/assignments/" + currentAssignmentId + "/tasks/" + taskId,
        acceptType:             'APPLICATION_JSON', 
        contentType:            'APPLICATION_JSON', 
        consoleLogResponseBody: true, 
        customHeaders: [[
            maskValue:          true, 
            name:               'authorization', 
            value:              cesToken
        ]], 
        ignoreSslErrors:        true, 
        responseHandle:         'NONE',         
        wrapAsMultipart:        false
    )

    return readJSON(text: response.getContent())
}

/* Get all versions for task */
def getComponentVersions(application, moduleName, moduleType, cesToken) {

    response = httpRequest(
        url:                    synchConfig.environment.ces.url + "/ispw/ispw/componentVersions/list?application=" + application + "&mname=" + moduleName + "&mtype=" + moduleType,
        acceptType:             'APPLICATION_JSON', 
        contentType:            'APPLICATION_JSON', 
        consoleLogResponseBody: true, 
        customHeaders: [[
            maskValue:          true, 
            name:               'authorization', 
            value:              cesToken
        ]], 
        ignoreSslErrors:        true, 
        responseHandle:         'NONE',         
        wrapAsMultipart:        false
    )

    return readJSON(text: response.getContent()).componentVersions
}

/* Get task list from assignment */
def getTasksForAssignment(assignmentId, cesToken) {

    response = httpRequest(
        url:                    synchConfig.environment.ces.url + "/ispw/ispw/assignments/" + assignmentId + "/tasks",
        acceptType:             'APPLICATION_JSON', 
        contentType:            'APPLICATION_JSON', 
        consoleLogResponseBody: true, 
        customHeaders: [[
            maskValue:          true, 
            name:               'authorization', 
            value:              cesToken
        ]], 
        ignoreSslErrors:        true, 
        responseHandle:         'NONE',         
        wrapAsMultipart:        false
    )

    return readJSON(text: response.getContent()).tasks
}

/* Determine task to take info from and return info*/
def getTaskSourceInfo(taskList, compareInfo) {

    def sourceTask

    for(task in taskList) {

        if(

            task.stream          == compareInfo.stream          &&
            task.application     == compareInfo.application     &&
            task.moduleName      == compareInfo.moduleName      &&
            task.moduleType      == compareInfo.moduleType      &&
            task.level           == compareInfo.startingLevel

        ){
            sourceTask = task
            break
        }
    }

    return sourceTask
}

def setNewTaskInfo(assignmentId, taskInfo, cesToken) {

    def jsonBody = writeJSON(returnText: true, json: taskInfo)

    echo "[Info] - Setting Generate Parms for component"
    echo "[Info] - Component                : " + taskInfo.moduleName
    echo "[Info] - Residing in Assignment   : " + assignmentId
    echo "[Info] - at level                 : " + taskInfo.currentLevel
    echo "[Info] - Based on level           : " + taskInfo.startingLevel

    response = httpRequest(
        url:                    synchConfig.environment.ces.url + "/ispw/ispw/assignments/" + assignmentId + "/tasks",
        httpMode:               'POST',
        acceptType:             'APPLICATION_JSON', 
        contentType:            'APPLICATION_JSON', 
        consoleLogResponseBody: true, 
        customHeaders: [[
            maskValue:          true, 
            name:               'authorization', 
            value:              cesToken
        ]], 
        requestBody:            jsonBody,        
        ignoreSslErrors:        true, 
        responseHandle:         'NONE',         
        wrapAsMultipart:        false
    )

    return readJSON(text: response.getContent()).taskId
}

/* Build mainframe code */
def runMainframeBuild(){

    ispwOperation(
        connectionId:           synchConfig.environment.hci.connectionId, 
        credentialsId:          pipelineParms.cesCredentialsId,       
        consoleLogResponseBody: true, 
        ispwAction:             'BuildTask', 
        ispwRequestBody:        '''buildautomatically = true'''
    )
}

def runUnitTests() {

    if(executionBehavior.executeVt){

        echo "[Info] - Execute Unit Tests at mainframe level " + ispwTargetLevel + "."

        totaltest(
            serverUrl:                          synchConfig.environment.ces.url, 
            serverCredentialsId:                pipelineParms.hostCredentialsId, 
            credentialsId:                      pipelineParms.hostCredentialsId, 
            environmentId:                      synchConfig.ttt.environmentIds.virtualized,
            localConfig:                        false, 
            //localConfigLocation:                tttConfigFolder, 
            folderPath:                         tttVtFolder, 
            recursive:                          true, 
            selectProgramsOption:               true, 
            jsonFile:                           synchConfig.ispw.changedProgramsFile,
            haltPipelineOnFailure:              false,                 
            stopIfTestFailsOrThresholdReached:  false,
            createJUnitReport:                  true, 
            createReport:                       true, 
            createResult:                       true, 
            createSonarReport:                  true,
            contextVariables:                   '"ispw_app=' + applicationQualifier + ',ispw_level=' + ispwTargetLevel + '"',
            collectCodeCoverage:                true,
            collectCCRepository:                pipelineParms.ccRepo,
            collectCCSystem:                    cocoParms.systemId,
            collectCCTestID:                    cocoParms.testId,
            clearCodeCoverage:                  false,
            logLevel:                           'INFO'
        )

        //secureResultsFile(sonarResultsFileVt, "Virtualized")

        junit allowEmptyResults: true, keepLongStdio: true, testResults: synchConfig.ttt.results.jUnit.folder + '/*.xml'
    }
    else{

        echo executionBehavior.skipReason + "\n[Info] - Skipping Unit Tests."

    }
}

def runIntegrationTests(){

    if(executionBehavior.executeNvt){

        echo "[Info] - Execute Module Integration Tests at mainframe level " + ispwTargetLevel + "."

        synchConfig.ttt.environmentIds.nonVirtualized.each {

            def envType     = it.key
            def envId       = it.value
            //def targetFile  = synchConfig.ttt.results.sonar.targetFiles.nonVirtualized[envType]

            totaltest(
                connectionId:                       synchConfig.environment.hci.connectionId,
                credentialsId:                      pipelineParms.hostCredentialsId,             
                serverUrl:                          synchConfig.environment.ces.url, 
                serverCredentialsId:                pipelineParms.hostCredentialsId, 
                environmentId:                      envId, 
                localConfig:                        false,
                folderPath:                         tttNvtFolder, 
                recursive:                          true, 
                selectProgramsOption:               true, 
                jsonFile:                           synchConfig.ispw.changedProgramsFile,
                haltPipelineOnFailure:              false,                 
                stopIfTestFailsOrThresholdReached:  false,
                createJUnitReport:                  true, 
                createReport:                       true, 
                createResult:                       true, 
                createSonarReport:                  true,
                contextVariables:                   '"nvt_ispw_app=' + applicationQualifier + 
                                                    ',nvt_ispw_level1=' + synchConfig.ttt.loadLibQualfiers[ispwTargetLevel].level1 + 
                                                    ',nvt_ispw_level2=' + synchConfig.ttt.loadLibQualfiers[ispwTargetLevel].level2 + 
                                                    ',nvt_ispw_level3=' + synchConfig.ttt.loadLibQualfiers[ispwTargetLevel].level3 + 
                                                    ',nvt_ispw_level4=' + synchConfig.ttt.loadLibQualfiers[ispwTargetLevel].level4 + 
                                                    '"',                
                collectCodeCoverage:                true,
                collectCCRepository:                pipelineParms.ccRepo,
                collectCCSystem:                    cocoParms.systemId,
                collectCCTestID:                    cocoParms.testId,
                clearCodeCoverage:                  false,
                logLevel:                           'INFO'
            )

            //secureResultsFile(targetFile, "Non Virtualized " + envType.toUpperCase())

        }
    }
    else{

        echo executionBehavior.skipReason + "\n[Info] - Skipping Integration Tests."

    }
}

// Each execution of totaltest will create a Sonar Results file with the same name (generated.cli.suite.sonar.xml)
// Therefore, if totaltest is being executed several time within one build, the corresponding result files need to be
// renamed to prevent overwriting
/* def secureResultsFile(resultsFileNameNew, resultsFileType) {

    try {

        bat label:  'Rename', 
            script: """
                cd ${sonarResultsFolder}
                ren ${sonarResultsFile} ${resultsFileNameNew}
            """

        echo "[Info] - A ${resultsFileType} results file was found.\n" + 
            "[Info] - ${resultsFileNameNew} will be processed."

        sonarResultsFileList.add(resultsFileNameNew)

    }
    catch(Exception e) {

        echo "[Warn] - No ${resultsFileType} Tests Results File could be found.\n" +
        "[Warn] - This may be because no matching test scenarios (environment or target program) were found.\n"
        "[Warn] - Refer to the Topaz for Total Test log output to determine if this is due to an issue or was to be expected.\n"
            
    }

    return
}
 */
def getCocoResults() {

    step([
        $class:             'CodeCoverageBuilder', 
        connectionId:       synchConfig.environment.hci.connectionId, 
        credentialsId:      pipelineParms.hostCredentialsId,
        analysisProperties: """
            cc.sources=${ccSources}
            cc.repos=${pipelineParms.ccRepo}
            cc.system=${cocoParms.systemId}
            cc.test=${cocoParms.testId}
            cc.ddio.overrides=${ccDdioOverrides}
        """
    ])
}

def runSonarScan() {

    def sonarTestResults        = ''
    def sonarTestsParm          = ''
    def sonarTestReportsParm    = ''
    def sonarCodeCoverageParm   = ''
    def scannerHome             = tool synchConfig.environment.sonar.scanner            

    def sonarProjectName

    if(pipelineParms.executionEnvironment == 'cwc2'){

        sonarProjectName = ispwConfig.ispwApplication.stream + '_CWC2_' + ispwConfig.ispwApplication.application

    }
    else{

        sonarProjectName = ispwConfig.ispwApplication.stream + '_' + ispwConfig.ispwApplication.application
    
    }

    if(executionBehavior.executeVt){

        sonarTestsParm          = ' -Dsonar.tests="' + tttRootFolder + '"'
        sonarTestReportsParm    = ' -Dsonar.testExecutionReportPaths="' + sonarResultsFolder + '/' + sonarResultsFileVt + '"'

        // Check if Code Coverage results have been retrieved. If not do not fail the build, as this is likely due to no tests been found
        try{
            readFile(file: sonarCodeCoverageFile)
            sonarCodeCoverageParm   = ' -Dsonar.coverageReportPaths=' + sonarCodeCoverageFile
        }
        catch(Exception e){
            sonarCodeCoverageParm   = ''
        }
    }

    withSonarQubeEnv(synchConfig.environment.sonar.server) {

        bat '"' + scannerHome + '/bin/sonar-scanner"' + 
            ' -Dsonar.branch.name=' + BRANCH_NAME +
            ' -Dsonar.projectKey=' + sonarProjectName + 
            ' -Dsonar.projectName=' + sonarProjectName +
            ' -Dsonar.projectVersion=1.0' +
            ' -Dsonar.sources=' + sonarCobolFolder + 
            ' -Dsonar.cobol.copy.directories=' + sonarCopybookFolder +
            ' -Dsonar.cobol.file.suffixes=cbl,testsuite,testscenario,stub,result,scenario,context' + 
            ' -Dsonar.cobol.copy.suffixes=cpy' +
            sonarTestsParm +
            sonarTestReportsParm +
            sonarCodeCoverageParm +
            ' -Dsonar.ws.timeout=480' +
            ' -Dsonar.sourceEncoding=UTF-8'

    }
}

def getMainAssignmentId(automaticBuildFile){

    def automaticBuildFileContent = readJSON(file: automaticBuildFile)

    return automaticBuildFileContent.containerId
    
}

def triggerXlRelease(){

    withCredentials(
        [
            string(
                credentialsId:  pipelineParms.cesCredentialsId, 
                variable:       'cesToken'
            ),
            usernamePassword(
                credentialsId:      pipelineParms.hostCredentialsId, 
                passwordVariable:   'pw', 
                usernameVariable:   'ownerId'
            )
        ]
    ) 
    {

        def assignmentId = getMainAssignmentId(synchConfig.ispw.automaticBuildFile)
/*
        echo    "XLRelease / digital.ai release is currently not available in the demo environment \n" + 
                "Instead use Jenkins job http://sonarqube.nasa.cpwr.corp:8080/view/Git2Ispw_Demo/job/GITDEMO_Workflow/job/GITDEMO_Run_Release/ to run a similar process. \n" + 
                "Use 'Build with parameters' and provide the following parameter values:\n" +
                "- ISPW_Application         : " + ispwConfig.ispwApplication.application + "\n" +
                "- ISPW_Assignment          : " + assignmentId + "\n" +
                "- Owner_Id                 : " + ownerId + "\n" +
                "- Host_Connection          : " + synchConfig.environment.hci.connectionId + "\n" +
                "- CES_Token                : " + cesToken + "\n" +
                "- Jenkins_CES_Credentials  : " + pipelineParms.cesCredentialsId + "\n" +
                "- Jenkins_Git_Credentials  : " + pipelineParms.gitCredentialsId + "\n" + 
                "- Release_Number           : " + "A 6 digit release number. This has to be unique within your ISPW demo application.\n" +
                "                             " + "The resulting release name in ISPW will be '" + ispwConfig.ispwApplication.application + "<Release_Number>',\n" + 
                "                             " + "and your Git repository will be tagged with 'v<Release_Number>."
*/

        xlrCreateRelease(
            releaseTitle:       "GITDEMO - Release for ${ispwConfig.ispwApplication.application}", 
            serverCredentials:  'admin', 
            startRelease:       true, 
            template:           synchConfig.environment.xlr.template, 
            variables: [
                [
                    propertyName:   'ISPW_Application', 
                    propertyValue:  ispwConfig.ispwApplication.application
                ], 
                [
                    propertyName:   'ISPW_Assignment', 
                    propertyValue:  assignmentId
                ], 
                [
                    propertyName:   'Owner_Id', 
                    propertyValue:  ownerId
                ],
                [
                    propertyName:   'CES_Token', 
                    propertyValue:  cesToken
                ], 
                [
                    propertyName: 'Jenkins_CES_Credentials', 
                    propertyValue: pipelineParms.cesCredentialsId
                ],
                [
                    propertyName: 'Jenkins_Git_Credentials', 
                    propertyValue: pipelineParms.gitCredentialsId
                ] 
            ]
        )
    }
}