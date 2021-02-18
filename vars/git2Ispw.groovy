#!/usr/bin/env groovy
import hudson.model.*
import hudson.EnvVars
import java.net.URL
import groovy.xml.*

String executionBranch      
String sharedLibName           
String synchConfigFolder       
String synchConfigFile         
String ispwConfigFile      
String ispwImpactScanFile
String automaticBuildFile  
String changedProgramsFile 
String branchMappingString     
String ispwTargetLevel
String ispwImpactScanJcl
String tttConfigFolder         
String tttVtExecutionLoad    
String tttUtJclSkeletonFile  
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

Boolean skipTests

def pipelineParms
def branchMapping             
def ispwConfig
def synchConfig
def automaticBuildInfo
def executionMapRule
def programList
def tttProjectList

def CC_TEST_ID_MAX_LEN
def CC_SYSTEM_ID_MAX_LEN

def EXECUTION_TYPE_NO_MF_CODE
def EXECUTION_TYPE_VT_ONLY
def EXECUTION_TYPE_NVT_ONLY
def EXECUTION_TYPE_BOTH

def RESULTS_FILE_VT
def RESULTS_FILE_NVT_BATCH
def RESULTS_FILE_NVT_CICS

def call(Map execParms){

    //**********************************************************************
    // Start of Script 
    //**********************************************************************
    node {

        stage ('Checkout and initialize') {

            dir('./') {
                deleteDir()
            }

            checkout scm

            initialize(execParms)

            //setVtLoadlibrary()  /* Will be replaced by using conext variables, once they are handled by the CLI correctly */

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

        checkForBuildParams()

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

        if(BRANCH_NAME == 'main') {

            triggerXlRelease()

        }
    }
}

def initialize(execParms){

    pipelineParms               = execParms

    CC_TEST_ID_MAX_LEN          = 15
    CC_SYSTEM_ID_MAX_LEN        = 15

    EXECUTION_TYPE_NO_MF_CODE   = "NoTests"
    EXECUTION_TYPE_VT_ONLY      = "Vt"
    EXECUTION_TYPE_NVT_ONLY     = "Nvt"
    EXECUTION_TYPE_BOTH         = "Both"

    RESULTS_FILE_VT             = 'Virtualized'
    RESULTS_FILE_NVT_BATCH      = 'Non Virtualized Batch'
    RESULTS_FILE_NVT_CICS       = 'Non Virtualized CICS'

    executionBranch             = BRANCH_NAME
    sharedLibName               = 'GITDEMO_Shared_Lib'
    synchConfigFile             = './git2ispw/synchronization.yml'
    ispwImpactScanFile          = './git2ispw/impact_scan.jcl'
    ispwConfigFileName          = 'ispwconfig.yml'
    automaticBuildFile          = './automaticBuildParams.txt'
    changedProgramsFile         = './changedPrograms.json'
    branchMappingString         = ''
    ispwTargetLevel             = ''    
    ispwImpactScanJcl           = ''
    tttConfigFolder             = ''
    tttVtExecutionLoad          = ''
    ccDdioOverrides             = ''
    sonarResultsFile            = 'generated.cli.suite.sonar.xml'
    sonarResultsFileVt          = 'generated.cli.vt.suite.sonar.xml'
    sonarResultsFileNvtBatch    = 'generated.cli.nvt.batch.suite.sonar.xml'
    sonarResultsFileNvtCics     = 'generated.cli.nvt.cics.suite.sonar.xml'
    sonarResultsFileList        = []    
    sonarResultsFolder          = './TTTSonar'
    sonarCodeCoverageFile       = './Coverage/CodeCoverage.xml'
    jUnitResultsFile            = './TTTUnit/generated.cli.suite.junit.xml'

    skipTests                   = false

    //*********************************************************************************
    // Read synchconfig.yml from Shared Library resources folder
    //*********************************************************************************
    def fileText                = libraryResource synchConfigFile
    synchConfig                 = readYaml(text: fileText)

    //*********************************************************************************
    // Build paths to subfolders of the project root
    //*********************************************************************************

    ispwConfigFile              = synchConfig.mfProjectRootFolder + '/' + ispwConfigFileName
    tttRootFolder               = synchConfig.mfProjectRootFolder + synchConfig.tttRootFolder
    tttVtFolder                 = tttRootFolder + synchConfig.tttVtFolder
    tttNvtFolder                = tttRootFolder + synchConfig.tttNvtFolder
    ccSources                   = synchConfig.mfProjectRootFolder + synchConfig.mfProjectSourcesFolder
    sonarCobolFolder            = synchConfig.mfProjectRootFolder + synchConfig.mfProjectSourcesFolder
    sonarCopybookFolder         = synchConfig.mfProjectRootFolder + synchConfig.mfProjectSourcesFolder

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

    processBranchInfo(synchConfig.branchInfo, ispwConfig.ispwApplication.application)

    //*********************************************************************************
    // If load library name is empty the branch name could not be mapped
    //*********************************************************************************
    if(tttVtExecutionLoad == ''){
        error "No branch mapping for branch ${executionBranch} was found. Execution will be aborted.\n" +
            "Correct the branch name to reflect naming conventions."
    }

    //*********************************************************************************
    // Build DDIO override parameter to use the VT load library (making use of ESS)
    //*********************************************************************************
    /*synchConfig.ccDdioOverrides.each {
        ccDdioOverrides = ccDdioOverrides + it.toString().replace('<ispwApplication>', ispwConfig.ispwApplication.application)
    }*/
    ccDdioOverrides             = tttVtExecutionLoad

    ispwImpactScanJcl           = buildImpactScanJcl(ispwImpactScanFile, ispwConfig.ispwApplication.runtimeConfig, ispwConfig.ispwApplication.application, ispwTargetLevel)

    //*********************************************************************************
    // The .tttcfg file and JCL skeleton are located in the pipeline shared library, resources folder
    // Determine path relative to current workspace
    //*********************************************************************************
    def tmpWorkspace            = workspace.replace('\\', '/')
    tttConfigFolder             = '..' + tmpWorkspace.substring(tmpWorkspace.lastIndexOf('/')) + '@libs/' + sharedLibName + '/resources' + '/' + synchConfig.tttConfigFolder
    tttUtJclSkeletonFile        = tttConfigFolder + '/JCLSkeletons/TTTRUNNER.jcl' 

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
    else if (executionBranch.contains("feature")) {
        executionType   = EXECUTION_TYPE_VT_ONLY
        skipReason      = "[Info] - '${branchName}' is a feature branch."
    }
    else if (executionBranch.contains("bugfix")) {
        executionType = EXECUTION_TYPE_VT_ONLY
        skipReason      = "[Info] - Branch '${branchName}'."
    }
    else if (executionBranch.contains("development")) {
        executionType   = EXECUTION_TYPE_BOTH
        skipReason      = "[Info] - Branch '${branchName}'."
    }
    else if (executionBranch.contains("main")) {
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
        if(executionBranch.contains(it.key)) {

            ispwTargetLevel     = it.value.ispwLevel
            tttVtExecutionLoad  = synchConfig.loadLibraryPattern.replace('<ispwApplication>', ispwApplication).replace('<ispwLevel>', ispwTargetLevel)

        }
    }
}

//*********************************************************************************
// Build JCL to scan for impacts once code has been loaded to the ISPW target level
//*********************************************************************************
def buildImpactScanJcl(ispwImpactScanFile, runtimeConfig, application, ispwTargetLevel){

echo "File: " + ispwImpactScanFile.toString()
echo "Config: " + runtimeConfig.toString()
echo "App: " + application.toString()
echo "Level: " + ispwTargetLevel.toString()

    ispwImpactScanJcl   = libraryResource ispwImpactScanFile

    ispwImpactScanJcl   = ispwImpactScanJcl.replace('<runtimeConfig>', runtimeConfig)
    ispwImpactScanJcl   = ispwImpactScanJcl.replace('<ispwApplication>', application)
    ispwImpactScanJcl   = ispwImpactScanJcl.replace('<ispwTargetLevel>', ispwTargetLevel)

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
/* Will be replaced by 20.05.01 feature                    */
def setVtLoadlibrary(){

    def jclSkeleton = readFile(tttUtJclSkeletonFile).toString().replace('${loadlibraries}', tttVtExecutionLoad)

    writeFile(
        file:   tttUtJclSkeletonFile,
        text:   jclSkeleton
    )    

}

def runMainframeLoad() {

    gitToIspwIntegration( 
        connectionId:       synchConfig.hciConnectionId,                    
        credentialsId:      pipelineParms.hostCredentialsId,                     
        runtimeConfig:      ispwConfig.ispwApplication.runtimeConfig,
        stream:             ispwConfig.ispwApplication.stream,
        app:                ispwConfig.ispwApplication.application, 
        branchMapping:      branchMappingString,
        ispwConfigPath:     ispwConfigFile, 
        gitCredentialsId:   pipelineParms.gitCredentialsId, 
        gitRepoUrl:         pipelineParms.gitRepoUrl
    )

    // try {

    //     gitToIspwIntegration( 
    //         connectionId:       synchConfig.hciConnectionId,                    
    //         credentialsId:      pipelineParms.hostCredentialsId,                     
    //         runtimeConfig:      ispwConfig.ispwApplication.runtimeConfig,
    //         stream:             ispwConfig.ispwApplication.stream,
    //         app:                ispwConfig.ispwApplication.application, 
    //         branchMapping:      branchMappingString,
    //         ispwConfigPath:     ispwConfigFile, 
    //         gitCredentialsId:   pipelineParms.gitCredentialsId, 
    //         gitRepoUrl:         pipelineParms.gitRepoUrl
    //     )

    // }
    // catch(Exception e) {

    //     echo "[Error] - Error during synchronisation to the mainframe.\n" +
    //          "[Error] - " + e.toString()

    //     currentBuild.result = 'FAILURE'

    //     skipReason = "[Info] - Due to error during synchronization."
    //     return

    // }
}

// If the automaticBuildParams.txt has not been created, it means no programs
// have been changed and the pipeline was triggered for other changes (e.g. in configuration files)
// These changes do not need to be "built".
def checkForBuildParams(){

    try {
        automaticBuildInfo = readJSON(file: automaticBuildFile)
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
        connectionId:       synchConfig.hciConnectionId, 
        credentialsId:      pipelineParms.hostCredentialsId, 
        jcl:                ispwImpactScanJcl, 
        maxConditionCode:   '4'
    )
}

/* Build mainframe code */
def runMainframeBuild(){

    ispwOperation(
        connectionId:           synchConfig.hciConnectionId, 
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
            serverUrl:                          synchConfig.cesUrl, 
            serverCredentialsId:                pipelineParms.hostCredentialsId, 
            credentialsId:                      pipelineParms.hostCredentialsId, 
            environmentId:                      synchConfig.tttVtEnvironmentId,
            localConfig:                        true, 
            localConfigLocation:                tttConfigFolder, 
            folderPath:                         tttVtFolder, 
            recursive:                          true, 
            selectProgramsOption:               true, 
            jsonFile:                           changedProgramsFile,
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

        secureResultsFile(sonarResultsFileVt, RESULTS_FILE_VT)

        junit allowEmptyResults: true, keepLongStdio: true, testResults: './TTTUnit/*.xml'
    }
}

def runIntegrationTests(){

    if(skipTests){

        echo skipReason + "\n[Info] - Skipping Integration Tests."

    }
    else{

        echo "[Info] - Execute Module Integration Tests at mainframe level " + ispwTargetLevel + "."

        /* Execute batch scenarios */
        totaltest(
            connectionId:                       synchConfig.hciConnectionId,
            credentialsId:                      pipelineParms.hostCredentialsId,             
            serverUrl:                          synchConfig.cesUrl, 
            serverCredentialsId:                pipelineParms.hostCredentialsId, 
            environmentId:                      synchConfig.tttNvtBatchEnvironmentId, 
            localConfig:                        false,
            folderPath:                         tttNvtFolder, 
            recursive:                          true, 
            selectProgramsOption:               true, 
            jsonFile:                           changedProgramsFile,
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

        secureResultsFile(sonarResultsFileNvtBatch, RESULTS_FILE_NVT_BATCH)

        /* Execute CICS scenarios */
        totaltest(
            connectionId:                       synchConfig.hciConnectionId,
            credentialsId:                      pipelineParms.hostCredentialsId,             
            serverUrl:                          synchConfig.cesUrl, 
            serverCredentialsId:                pipelineParms.hostCredentialsId, 
            environmentId:                      synchConfig.tttNvtCicsEnvironmentId, 
            localConfig:                        false,
            folderPath:                         tttNvtFolder, 
            recursive:                          true, 
            selectProgramsOption:               true, 
            jsonFile:                           changedProgramsFile,
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

        secureResultsFile(sonarResultsFileNvtCics, RESULTS_FILE_NVT_CICS)
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
        connectionId:       synchConfig.hciConnectionId, 
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
    def scannerHome             = tool synchConfig.sonarScanner            

    if(executionType == EXECUTION_TYPE_VT_ONLY | executionType == EXECUTION_TYPE_BOTH){

        //sonarTestResults        = getSonarResults(sonarResultsFileList)
        sonarTestsParm          = ' -Dsonar.tests="' + tttRootFolder + '"'
        //sonarTestReportsParm    = ' -Dsonar.testExecutionReportPaths="' + sonarTestResults + '"'
        sonarTestReportsParm    = ' -Dsonar.testExecutionReportPaths="' + sonarResultsFolder + '/' + sonarResultsFileVt + '"'
        sonarCodeCoverageParm   = ' -Dsonar.coverageReportPaths=' + sonarCodeCoverageFile

    }

    withSonarQubeEnv(synchConfig.sonarServer) {

        bat '"' + scannerHome + '/bin/sonar-scanner"' + 
            ' -Dsonar.branch.name=' + executionBranch +
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

def getMainAssignmentId(){

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

    def assignmentId = getMainAssignmentId()

    xlrCreateRelease(
        releaseTitle:       "GITDEMO - Release for ${ispwConfig.ispwApplication.application}", 
        serverCredentials:  'admin', 
        startRelease:       true, 
        template:           'GITDEMO_CWCC', 
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