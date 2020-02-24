package io.infinite.cobol

import com.ibm.dataaccess.DecimalData
import groovy.util.logging.Slf4j
import io.infinite.blackbox.BlackBox
import io.infinite.carburetor.CarburetorLevel
import io.infinite.supplies.ast.other.ASTUtils

@BlackBox(level = CarburetorLevel.ERROR)
@Slf4j
class CobolRuntime {

    protected static Integer runtimeCounter = 0

    protected Integer runtimeId

    protected Long totalSize

    protected InputStream inputStream

    protected String charsetName

    protected List<Byte> lineBreakBytes

    protected Boolean streamFinished = false

    protected Integer totalRead = 0

    protected Integer totalSkipped = 0

    protected CobolGroup currentGroup = new CobolGroup()

    protected CobolApi cobolApi

    protected Boolean isFirstRecord = true

    protected Boolean isLastRecord = false

    protected CopybookStructureEnum copybookStructure
//flag to identify copybooks with header(1)->data records(0, 1 or more)->trailer(1) structure

    long lastLoggedPercentatge = 0

    /**
     * Runs COBOL code compiled using Cobol Compiler.<br>
     * This method is overridden in dynamic child classes generated by io.infinite.cobol.CobolCompiler<br>
     * Use io.infinite.cobol.CobolCompiler to get executable runtime.
     *
     * @param totalSize total file size (bytes)
     * @param inputStream File input stream
     * @param charsetName COBOL file encoding, e.g. 037 or latin1
     * @param lineBreakBytes optional - if COBOL data file is already split by lines, e.g. [(byte) 13, (byte) 10] for crlf
     */
    void run(Long totalSize, InputStream inputStream, String charsetName, List<Byte> lineBreakBytes, CobolApi cobolApi, CopybookStructureEnum copybookStructure) {
        //this method is overridden in dynamic child classes generated by CobolCompiler
        throw new CobolExceptionRuntime("Use CobolCompiler to create an executable Cobol Runtime")
    }

    protected void setup(Long totalSize, InputStream inputStream, String charsetName, List<Byte> lineBreakBytes, CobolApi cobolApi, CopybookStructureEnum copybookStructure) {
        runtimeCounter += 1
        runtimeId = runtimeCounter
        this.totalSize = totalSize
        this.inputStream = inputStream
        this.charsetName = charsetName
        this.lineBreakBytes = lineBreakBytes
        this.cobolApi = cobolApi
        this.copybookStructure = copybookStructure
    }

    private void runCobolClosure(Closure cobolClosure) {
        cobolClosure.setDelegate(this)
        new ASTUtils().ensureClosureEquivalency(cobolClosure, this)
        cobolClosure.call()//<<<<<<<<<<<<<<<<<<<<
    }

    protected void readFile(Closure fileClosure) {
        log.debug("Starting to read COBOL data file.")
        cobolApi.fileStart()
        fileClosure.call()//<<<<<<<<<<<<<<<<<<<<
        cobolApi.fileEnd()
        runtimeCounter = 0
        log.debug("Finished reading COBOL data file.")
    }

    protected void createRecord(String recordName, Closure recordClosure) {
        while (!streamFinished) {
            Long initialTotalRead = totalRead
            currentGroup = new CobolGroup()
            cobolApi.recordStart(recordName)
            recordClosure.call()//<<<<<<<<<<<<<<<<<<<<
            cobolApi.recordEnd(recordName)
            if (!lineBreakBytes.isEmpty()) {
                //DISABLED DEBUG:log.debug("Reading line break.")
                byte[] lineBreakSequence = new byte[lineBreakBytes.size()]
                Integer readBytes = inputStream.read(lineBreakSequence)
                totalSkipped += readBytes
                if (readBytes < lineBreakBytes.size()) {
                    streamFinished = true
                } else {
                    if (lineBreakSequence != lineBreakBytes) {//IDE warning should be ignored here, it works ok.
                        throw new CobolExceptionRuntime("Unexpected bytes read $lineBreakSequence; expecting line break $lineBreakBytes.")
                    }
                }
            }
            if (totalSize == (totalRead + totalSkipped)) {
                log.debug("Read all bytes.")
                streamFinished = true
            }
            if (totalSize < (totalRead + totalSkipped)) {
                throw new CobolExceptionRuntime("Over reading. Internal error (bug) - this should not happen. Investigation is required.")
            }
            if ([CopybookStructureEnum.THREE_RECORD, CopybookStructureEnum.TWO_RECORD_H].contains(copybookStructure) && isFirstRecord) {
                //DISABLED DEBUG:log.debug("Copybook structure with header. Header read - proceeding to read details.")
                isFirstRecord = false
                break
            }
            if ([CopybookStructureEnum.THREE_RECORD, CopybookStructureEnum.TWO_RECORD_T].contains(copybookStructure) && !isLastRecord) {
                Long lastRecordSize = totalRead - initialTotalRead
                //DISABLED DEBUG:log.debug("Last record/trailer check: totalSize=${totalSize}, ${totalRead} + ${lastRecordSize} = ${totalRead + lastRecordSize}")
                if (totalSize <= totalRead + lastRecordSize) {
                    //DISABLED DEBUG:log.debug("Next record will be treated as last in the file (trailer)")
                    isLastRecord = true
                    break
                }
            }
            if (currentGroup.childGroups.isEmpty()) {
                throw new CobolExceptionRuntime("Empty records are not supported.")
            }
            if (runtimeId == 1) {
                long percentageComplete = Math.round(calculatePercentage(totalRead, totalSize))
                if (percentageComplete > lastLoggedPercentatge) {
                    log.debug("Completed: $percentageComplete%")
                    lastLoggedPercentatge = percentageComplete
                }
            }
        }
    }

    protected void createGroup(Integer depth, String groupName, Closure groupClosure) {
        CobolGroup initialGroup = currentGroup
        CobolGroup newGroup = new CobolGroup(parentGroup: currentGroup, depth: depth, groupName: groupName)
        currentGroup.childGroups.add(newGroup)
        currentGroup = newGroup
        cobolApi.groupStart(newGroup)
        groupClosure.call()//<<<<<<<<<<<<<<<<<<<<<<<<<<
        if (currentGroup.pictureString != null) {
            readField()
        }
        currentGroup.parentGroup.rawData.addAll(currentGroup.rawData)
        cobolApi.groupEnd(newGroup)
        currentGroup = initialGroup
    }

    double calculatePercentage(double obtained, double total) {
        return obtained * 100 / total
    }

    protected void readField() {
        //Precision: Total number of significant digits
        //Scale: Number of digits to the right of the decimal point
        if (currentGroup.pictureString.startsWith("S")) {
            currentGroup.isSigned = true
            currentGroup.pictureString = currentGroup.pictureString.substring(1)
        }
        byte[] bytes
        if (currentGroup.usageType == null) {
            bytes = new byte[currentGroup.length]
        } else if (currentGroup.usageType == "COMP_3") {
            bytes = new byte[currentGroup.comp3length]
        } else {
            throw new CobolExceptionRuntime("Unsupported usage type: " + currentGroup.usageType)
        }
        int howManyRead = inputStream.read(bytes)
        totalRead += howManyRead
        currentGroup.rawData = Arrays.asList(bytes) as List<Byte>
        //log.debug("Read: " + howManyRead + " : " + bytes, " : total read : $totalRead (runtime id $runtimeId)")
        //DISABLED DEBUG:log.debug("Read: " + howManyRead + " : total read : $totalRead (runtime id $runtimeId)")
        if (currentGroup.usageType == null) {
            cobolApi.importField(currentGroup, new String(bytes, charsetName))
        } else {
            //DISABLED DEBUG:log.debug("binary data (${currentGroup.pictureString}), signed = ${currentGroup.isSigned}")
            Integer precision = currentGroup.length
            cobolApi.importField(currentGroup, DecimalData.convertPackedDecimalToBigDecimal(//todo: test (compare with known values)
                    bytes,
                    0,
                    precision,
                    currentGroup.scale,
                    true
            ).toString())
        }
        if (howManyRead <= 0) {
            log.debug("Finished reading. Total read: $totalRead (runtime id $runtimeId)")
            streamFinished = true
        }
    }

    protected void setUsageType(String usageType) {
        currentGroup.usageType = usageType
    }

    protected void setDataPicture(Map args) {
        currentGroup.pictureString = args.get("pictureString") as String
        currentGroup.length = args.get("length") as Integer
        currentGroup.comp3length = args.get("comp3length") as Integer
        currentGroup.scale = args.get("scale") as Integer
    }

    protected void redefineGroup(String whichGroup, Closure redefineClosure) {
        //DISABLED DEBUG:log.debug("${currentGroup.groupName} redefines ${whichGroup}.")
        if (currentGroup.groupName == whichGroup) {
            throw new CobolExceptionRuntime("Redefined and redefining group names are same: " + whichGroup)
        }
        //DISABLED DEBUG:log.debug("Searching for redefined groups in the parent group: " + currentGroup.parentGroup.groupName)
        List<CobolGroup> redefinedGroups = currentGroup.parentGroup.childGroups.findAll {
            it.groupName == whichGroup
        }
        if (redefinedGroups.isEmpty()) {
            throw new CobolExceptionRuntime("Redefined groups not found: " + whichGroup)
        } else {
            //DISABLED DEBUG:log.debug("Found redefined groups: " + redefinedGroups.size())
            CobolGroup initialGroup = currentGroup
            redefinedGroups.each {
                //log.debug(" - ${it.groupName} : ${it.rawData.length} : ${it.rawData}")
                //DISABLED DEBUG:log.debug(" - ${it.groupName} : ${it.rawData.length}")
                cobolApi.redefinitionStart(initialGroup)
                CobolRuntime separateRuntime = this.getClass().newInstance() as CobolRuntime
                separateRuntime.setup(
                        it.rawData.size(),
                        new ByteArrayInputStream(it.rawData.toArray() as byte[]),
                        charsetName,
                        lineBreakBytes,
                        cobolApi,
                        copybookStructure
                )
                separateRuntime.runCobolClosure(redefineClosure)
                cobolApi.redefinitionEnd(initialGroup)
            }
        }
    }

    protected void addValues(List<String> values) {
        values.each {
            cobolApi.importConstant(currentGroup, it)
        }
    }

    protected void reoccurGroup(int howMany, Closure occursClosure) {
        CobolGroup initialGroup = currentGroup
        (1..howMany).each {
            //DISABLED DEBUG:log.debug("Reoccurrence: $currentGroup.groupName : $it")
            cobolApi.occurrenceStart(initialGroup)
            occursClosure.call()//<<<<<<<<<<<<<<<
            cobolApi.occurrenceEnd(initialGroup)
        }
    }
}
