#!/usr/bin/env groovy
import hudson.model.*
import hudson.EnvVars
import java.net.URL
import groovy.xml.*

String synchConfigFile         
String branchMappingString     
String ispwTargetLevel
String ispwImpactScanJcl
String ccDdioOverrides     
String sonarCobolFolder        
String sonarCopybookFolder     
String sonarResultsFile   
String sonarResultsFileVt
String sonarResultsFileNvtBatch
String sonarResultsFileNvtCics
String sonarResultsFileList     
String sonarCodeCoverageFile   
String jUnitResultsFile
String executionType
String skipReason

String tttVtExecutionLoad

Boolean skipTests

def pipelineParms
def ispwConfig
def synchConfig

def CC_TEST_ID_MAX_LEN
def CC_SYSTEM_ID_MAX_LEN

def EXECUTION_TYPE_NO_MF_CODE
def EXECUTION_TYPE_VT_ONLY
def EXECUTION_TYPE_NVT_ONLY
def EXECUTION_TYPE_BOTH

def call(Map execParms){

    //**********************************************************************
    // Start of Script 
    //**********************************************************************

    stage ('Initialize') {

        if(!(BUILD_NUMBER == "1")) {

            dir('./') {
                deleteDir()
            }

            unstash name: 'workspace'

        }
        
        initialize(execParms)

        //setVtLoadlibrary()  /* Replaced by using context variables */

    }

    stage('Load code to mainframe') {

        if(executionType == EXECUTION_TYPE_NO_MF_CODE) {

            echo skipReason + "\n[Info] - No code will be loaded to the mainframe."

        }
        else {

            echo "[Info] - Loading code to mainframe level " + ispwTargetLevel + "."

            runMainframeLoad()

        }
    }

    checkForBuildParams(synchConfig.ispw.automaticBuildFile)

    stage('Build mainframe code') {

        if(executionType == EXECUTION_TYPE_NO_MF_CODE){

            echo skipReason + "\n[Info] - Skipping Mainframe Build."

        }
        else{

            echo "[Info] - Building code at mainframe level " + ispwTargetLevel + "."

            runImpactScan()

            runMainframeBuild()

        }
    }

    if(
        executionType == EXECUTION_TYPE_VT_ONLY ||
        executionType == EXECUTION_TYPE_BOTH
    ){

        stage('Execute Unit Tests') {           

            runUnitTests()

        }
    }

    if(
        executionType == EXECUTION_TYPE_NVT_ONLY ||
        executionType == EXECUTION_TYPE_BOTH
    ){

        stage('Execute Module Integration Tests') {

            runIntegrationTests()

        }
    }

    if(!(executionType == EXECUTION_TYPE_NO_MF_CODE)){

        getCocoResults()

    }
        
    stage("SonarQube Scan") {

        runSonarScan()

    }   

    if(
        BRANCH_NAME     == 'main'                       &
        !(executionType == EXECUTION_TYPE_NO_MF_CODE)
    ){

        stage("Trigger Release") {

            triggerXlRelease()
        
        }
    }
}

def initialize(execParms){

    synchConfigFile             = './git2ispw/synchronization.yml'

    pipelineParms               = execParms

    CC_TEST_ID_MAX_LEN          = 15
    CC_SYSTEM_ID_MAX_LEN        = 15

    EXECUTION_TYPE_NO_MF_CODE   = "NoTests"
    EXECUTION_TYPE_VT_ONLY      = "Vt"
    EXECUTION_TYPE_NVT_ONLY     = "Nvt"
    EXECUTION_TYPE_BOTH         = "Both"

    branchMappingString         = ''
    ispwTargetLevel             = ''    
    ispwImpactScanJcl           = ''
    tttVtExecutionLoad          = ''
    ccDdioOverrides             = ''

    skipTests                   = false

    //*********************************************************************************
    // Read synchconfig.yml from Shared Library resources folder
    //*********************************************************************************
    def fileText                = libraryResource synchConfigFile
    synchConfig                 = readYaml(text: fileText)

    //*********************************************************************************
    // Build paths to subfolders of the project root
    //*********************************************************************************

    ispwConfigFile              = synchConfig.ispw.configFile.folder    + '/' + synchConfig.ispw.configFile.name
    tttRootFolder               = synchConfig.ispw.mfProject.rootFolder + synchConfig.ttt.folders.root
    tttVtFolder                 = tttRootFolder                         + synchConfig.ttt.folders.virtualizedTests
    tttNvtFolder                = tttRootFolder                         + synchConfig.ttt.folders.nonVirtualizedTests
    ccSources                   = synchConfig.ispw.mfProject.rootFolder + synchConfig.ispw.mfProject.sourcesFolder
    sonarCobolFolder            = synchConfig.ispw.mfProject.rootFolder + synchConfig.ispw.mfProject.sourcesFolder
    sonarCopybookFolder         = synchConfig.ispw.mfProject.rootFolder + synchConfig.ispw.mfProject.sourcesFolder

    sonarResultsFolder          = synchConfig.ttt.results.sonar.folder
    sonarResultsFile            = synchConfig.ttt.results.sonar.origFile
    sonarResultsFileVt          = synchConfig.ttt.results.sonar.targetFiles.virtualized
    sonarResultsFileNvtBatch    = synchConfig.ttt.results.sonar.targetFiles.nonVirtualized.batch
    sonarResultsFileNvtCics     = synchConfig.ttt.results.sonar.targetFiles.nonVirtualized.cics
    sonarResultsFileList        = []        

    sonarCodeCoverageFile       = synchConfig.coco.results.sonar.folder + '/' + synchConfig.coco.results.sonar.file
    
    jUnitResultsFile            = synchConfig.ttt.results.jUnit.folder  + '/' + synchConfig.ttt.results.jUnit.file

    //*********************************************************************************
    // Read ispwconfig.yml
    // Strip the first line of ispwconfig.yml because readYaml can't handle the !! tag
    //*********************************************************************************
    def tmpText                 = readFile(file: ispwConfigFile)

    // remove the first line (i.e. the substring following the first carriage return '\n')
    tmpText                     = tmpText.substring(tmpText.indexOf('\n') + 1)

    // convert the text to yaml
    ispwConfig                  = readYaml(text: tmpText)

    determinePipelineBehavior(BRANCH_NAME, BUILD_NUMBER)

    processBranchInfo(synchConfig.ispw.branchInfo, ispwConfig.ispwApplication.application)

    //*********************************************************************************
    // If target level is empty the branch name could not be mapped
    //*********************************************************************************
    if(ispwTargetLevel == ''){
        error "No branch mapping for branch ${BRANCH_NAME} was found. Execution will be aborted.\n" +
            "Correct the branch name to reflect naming conventions."
    }

    //*********************************************************************************
    // Build DDIO override parameter to use the VT load library (making use of ESS)
    //
    // +++++++++++++++++++
    // Can be replaced once CoCo PTF has been applied to CWCC making use of overrides obsolete
    // +++++++++++++++++++    
    //*********************************************************************************
    ccDdioOverrides             = tttVtExecutionLoad

    //*********************************************************************************
    // Build JCL to cross reference components once they have been loaded to ISPW
    //
    // +++++++++++++++++++
    // Can be replaced once this feature has been implemented in ISPW itself
    // +++++++++++++++++++    
    //*********************************************************************************
    ispwImpactScanJcl           = buildImpactScanJcl(synchConfig.ispw.impactScanFile, ispwConfig.ispwApplication.runtimeConfig, ispwConfig.ispwApplication.application, ispwTargetLevel)

    //*********************************************************************************
    // The .tttcfg file and JCL skeleton are located in the pipeline shared library, resources folder
    // Determine path relative to current workspace
    //
    // Replaced by using context vars and server configuration
    //*********************************************************************************
    //def tmpWorkspace            = workspace.replace('\\', '/')
    //tttConfigFolder             = '..' + tmpWorkspace.substring(tmpWorkspace.lastIndexOf('/')) + '@libs/' + sharedLibName + '/resources' + '/' + synchConfig.tttConfigFolder
    //tttUtJclSkeletonFile        = tttConfigFolder + '/JCLSkeletons/TTTRUNNER.jcl' 

    buildCocoParms(BRANCH_NAME)

}

/* Determine execution type of the pipeline */
/* If it executes for the first time, i.e. the branch has just been created, only scan sources and don't execute any tests      */
/* Else, depending on the branch type or branch name (feature, development, fix or main) determine the type of tests to execute */
def determinePipelineBehavior(branchName, buildNumber){

    if (buildNumber == "1") {
        executionType   = EXECUTION_TYPE_NO_MF_CODE
        skipTests       = true
        skipReason      = "[Info] - First build for branch '${branchName}'. Onyl sources will be scanned by SonarQube"
    }    
    else if (BRANCH_NAME.contains("feature")) {
        executionType   = EXECUTION_TYPE_VT_ONLY
        skipReason      = "[Info] - '${branchName}' is a feature branch."
    }
    else if (BRANCH_NAME.contains("bugfix")) {
        executionType = EXECUTION_TYPE_VT_ONLY
        skipReason      = "[Info] - Branch '${branchName}'."
    }
    else if (BRANCH_NAME.contains("development")) {
        executionType   = EXECUTION_TYPE_BOTH
        skipReason      = "[Info] - Branch '${branchName}'."
    }
    else if (BRANCH_NAME.contains("main")) {
        executionType   = EXECUTION_TYPE_NVT_ONLY
        skipReason      = "[Info] - Branch '${branchName}'."
    }
}

//*********************************************************************************
// Build branch mapping string to be used as parameter in the gitToIspwIntegration
// Build load library name from configuration, replacing application marker by actual name
//*********************************************************************************
def processBranchInfo(branchInfo, ispwApplication){

    branchInfo.each {

        branchMappingString = branchMappingString + it.key + '** => ' + it.value.ispwLevel + ',' + it.value.mapRule + '\n'

        /* Get target Level and load bib for VTs from branch Mapping info for current build cranch */
        if(BRANCH_NAME.contains(it.key)) {

            ispwTargetLevel     = it.value.ispwLevel
            
            // May be removed once CoCo PTF has been applied
            // +++++++++++++++++++++++++++++++++++++++++++++
            tttVtExecutionLoad  = synchConfig.ttt.loadLibraryPattern.replace('<ispwApplication>', ispwApplication).replace('<ispwLevel>', ispwTargetLevel)
            // +++++++++++++++++++++++++++++++++++++++++++++
        }
    }
}

//*********************************************************************************
// Build JCL to scan for impacts once code has been loaded to the ISPW target level
//*********************************************************************************
//
// +++++++++++++++++++
// Can be replaced once this feature has been implemented in ISPW itself
// +++++++++++++++++++    
//*********************************************************************************
def buildImpactScanJcl(impactScanFile, runtimeConfig, application, ispwTargetLevel){

    jcl   = libraryResource impactScanFile
    jcl   = ispwImpactScanJcl.replace('<runtimeConfig>', runtimeConfig)
    jcl   = ispwImpactScanJcl.replace('<ispwApplication>', application)
    jcl   = ispwImpactScanJcl.replace('<ispwTargetLevel>', ispwTargetLevel)

    return jcl
}

//*********************************************************************************
// Build Code Coverage System ID from current branch, System ID must not be longer than 15 characters
// Build Code Coverage Test ID from Build Number
//*********************************************************************************
def buildCocoParms(executionBranch){

    if(executionBranch.length() > CC_SYSTEM_ID_MAX_LEN) {
        ccSystemId  = executionBranch.substring(executionBranch.length() - CC_SYSTEM_ID_MAX_LEN)
    }
    else {
        ccSystemId  = executionBranch
    }
    
    ccTestId    = BUILD_NUMBER

}

/* Modify JCL Skeleton to use correct load library for VTs */
/* Replaced by 20.05.01 context variables                  */
// def setVtLoadlibrary(){

//     def jclSkeleton = readFile(tttUtJclSkeletonFile).toString().replace('${loadlibraries}', tttVtExecutionLoad)

//     writeFile(
//         file:   tttUtJclSkeletonFile,
//         text:   jclSkeleton
//     )    

// }

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

        executionType   = EXECUTION_TYPE_NO_MF_CODE
        skipTests       = true
        skipReason      = skipReason + "\n[Info] - No changes to mainframe code."

    }
}

/* After loading code to ISPW execute job to initiate impacts scan */
def runImpactScan(){

    topazSubmitFreeFormJcl(
        connectionId:       synchConfig.environment.hci.connectionId, 
        credentialsId:      pipelineParms.hostCredentialsId, 
        jcl:                ispwImpactScanJcl, 
        maxConditionCode:   '4'
    )
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

    if(skipTests){

        echo skipReason + "\n[Info] - Skipping Unit Tests."

    }
    else{
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
            contextVariables:                   '"ispw_app=' + ispwConfig.ispwApplication.application + ',ispw_level=' + ispwTargetLevel + '"',
            collectCodeCoverage:                true,
            collectCCRepository:                pipelineParms.ccRepo,
            collectCCSystem:                    ccSystemId,
            collectCCTestID:                    ccTestId,
            clearCodeCoverage:                  false,
            logLevel:                           'INFO'
        )

        secureResultsFile(sonarResultsFileVt, "Virtualized")

        junit allowEmptyResults: true, keepLongStdio: true, testResults: synchConfig.ttt.results.jUnit.folder + '/*.xml'
    }
}

def runIntegrationTests(){

    if(skipTests){

        echo skipReason + "\n[Info] - Skipping Integration Tests."

    }
    else{

        echo "[Info] - Execute Module Integration Tests at mainframe level " + ispwTargetLevel + "."

        synchConfig.ttt.environmentIds.nonVirtualized.each {

            def envType     = it.key
            def envId       = it.value
            def targetFile  = synchConfig.ttt.results.sonar.targetFiles.nonVirtualized[envType]

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
                collectCodeCoverage:                true,
                collectCCRepository:                pipelineParms.ccRepo,
                collectCCSystem:                    ccSystemId,
                collectCCTestID:                    ccTestId,
                clearCodeCoverage:                  false,
            //    ccThreshold:                        pipelineParms.ccThreshold,     
                logLevel:                           'INFO'
            )

            secureResultsFile(targetFile, "Non Virtualized " + envType.toUpperCase())

        }
    }
}

def secureResultsFile(resultsFileNameNew, resultsFileType) {

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

        echo "[Info] - No ${resultsFileType} Tests needed to be executed.\n" +
        "[Info] - Therefore, no ${resultsFileType} results file needs to be processed."
            
    }

    return
}

def getCocoResults() {

    step([
        $class:             'CodeCoverageBuilder', 
        connectionId:       synchConfig.environment.hci.connectionId, 
        credentialsId:      pipelineParms.hostCredentialsId,
        analysisProperties: """
            cc.sources=${ccSources}
            cc.repos=${pipelineParms.ccRepo}
            cc.system=${ccSystemId}
            cc.test=${ccTestId}
            cc.ddio.overrides=${ccDdioOverrides}
        """
    ])
}

def runSonarScan() {

    def sonarBranchParm         = ''
    def sonarTestResults        = ''
    def sonarTestsParm          = ''
    def sonarTestReportsParm    = ''
    def sonarCodeCoverageParm   = ''
    def scannerHome             = tool synchConfig.environment.sonar.scanner            

    if(executionType == EXECUTION_TYPE_VT_ONLY | executionType == EXECUTION_TYPE_BOTH){

        //sonarTestResults        = getSonarResults(sonarResultsFileList)
        sonarTestsParm          = ' -Dsonar.tests="' + tttRootFolder + '"'
        //sonarTestReportsParm    = ' -Dsonar.testExecutionReportPaths="' + sonarTestResults + '"'
        sonarTestReportsParm    = ' -Dsonar.testExecutionReportPaths="' + sonarResultsFolder + '/' + sonarResultsFileVt + '"'
        sonarCodeCoverageParm   = ' -Dsonar.coverageReportPaths=' + sonarCodeCoverageFile

    }

    withSonarQubeEnv(synchConfig.environment.sonar.server) {

        bat '"' + scannerHome + '/bin/sonar-scanner"' + 
            ' -Dsonar.branch.name=' + BRANCH_NAME +
            ' -Dsonar.projectKey=' + ispwConfig.ispwApplication.stream + '_' + ispwConfig.ispwApplication.application + 
            ' -Dsonar.projectName=' + ispwConfig.ispwApplication.stream + '_' + ispwConfig.ispwApplication.application +
            ' -Dsonar.projectVersion=1.0' +
            ' -Dsonar.sources=' + sonarCobolFolder + 
            ' -Dsonar.cobol.copy.directories=' + sonarCopybookFolder +
            ' -Dsonar.cobol.file.suffixes=cbl,testsuite,testscenario,stub,result' + 
            ' -Dsonar.cobol.copy.suffixes=cpy' +
            sonarTestsParm +
            sonarTestReportsParm +
            sonarCodeCoverageParm +
            ' -Dsonar.ws.timeout=480' +
            ' -Dsonar.sourceEncoding=UTF-8'

    }
}

def getSonarResults(resultsFileList){

    def resultsList         = ''

    resultsFileList.each{

        def resultsFileContent
        def resultsFileName = it
        resultsFileContent  = readFile(file: sonarResultsFolder + '/' + it)
        resultsFileContent  = resultsFileContent.substring(resultsFileContent.indexOf('\n') + 1)
        def testExecutions  = new XmlSlurper().parseText(resultsFileContent)
        /* For now - only pass VT sonar results to sonar */
        testExecutions.file.each {
            
            if(resultsFileName.contains('.vt.')){
                resultsList = resultsList + it.@path.toString().replace('.Jenkins.result', '.sonar.xml') + ','
            }
        }
    }

    return resultsList
}

def getMainAssignmentId(automaticBuildFile){

    def automaticBuildFileContent = readJSON(file: automaticBuildFile)

    return automaticBuildFileContent.containerId
    
}

def triggerXlRelease(){

    def cesToken 
    def ownerId

    withCredentials(
        [
            string(
                credentialsId:  pipelineParms.cesCredentialsId, 
                variable:       'cesTokenTemp'
            )
        ]
    ) 
    {

        cesToken = cesTokenTemp

    }

    withCredentials(
        [
            usernamePassword(
                credentialsId:      pipelineParms.hostCredentialsId, 
                passwordVariable:   'pw', 
                usernameVariable:   'ownerIdTemp'
            )
        ]
    ) 
    {

        ownerId = ownerIdTemp

    }

    def assignmentId = getMainAssignmentId(synchConfig.ispw.automaticBuildFile)

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