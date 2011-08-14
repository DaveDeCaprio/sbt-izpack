/*
  ---------------------------------------------------------------------------
  This software is released under a BSD license, adapted from
  http://opensource.org/licenses/bsd-license.php

  Copyright (c) 2010-2011, Brian M. Clapper
  All rights reserved.

  Redistribution and use in source and binary forms, with or without
  modification, are permitted provided that the following conditions are
  met:

  * Redistributions of source code must retain the above copyright notice,
    this list of conditions and the following disclaimer.

  * Redistributions in binary form must reproduce the above copyright
    notice, this list of conditions and the following disclaimer in the
    documentation and/or other materials provided with the distribution.

  * Neither the names "clapper.org", "Era", nor the names of its
    contributors may be used to endorse or promote products derived from
    this software without specific prior written permission.

  THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS
  IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO,
  THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR
  PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR
  CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL,
  EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO,
  PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR
  PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF
  LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING
  NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
  SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
  ---------------------------------------------------------------------------
*/

package org.clapper.sbt

import scala.xml.{Comment => XMLComment,
                  Elem => XMLElem,
                  MetaData => XMLMetaData,
                  Node => XMLNode,
                  NodeSeq => XMLNodeSeq,
                  Null => XMLNull,
                  PrettyPrinter => XMLPrettyPrinter,
                  Text => XMLText,
                  TopScope => XMLTopScope,
                  UnprefixedAttribute}

import scala.collection.mutable.{ListBuffer,
                                 Map => MutableMap,
                                 HashSet => MutableSet}
import sbt._

// ------------------------------------------------------------------------
// Traits and Utilities
// ------------------------------------------------------------------------

/**
 * A globber. Exists larger to hide the much richer PathFinder, which conflicts
 * with RichFile in our implicits.
 */
class FileSetGlob(val path: File)
{
    def this(s: String) = this(new File(s))

    def **(s: String) = PathFinder(new File(s))
}

/**
 * Implemented by config-related classes that can take an operating
 * system constraint. Assumes that the resulting XML can contain
 * an `<os family="osname"/>` element.
 */
trait OperatingSystemConstraints
{
    var operatingSystems = new MutableSet[String]

    def onlyFor(osNames: String*) =
        for (os <- osNames)
            operatingSystems += os

    def operatingSystemsToXML =
        operatingSystems.map(os => <os family={os}/>)
}

/**
 * Useful string methods
 */
private[sbt] class XString(val str: String)
{
    /**
     * Convenience method to check for a string that's null or empty.
     */
    def isEmpty = (str == null) || (str.trim == "")

    /**
     * Convert the string to an option. An empty or null string
     * is converted to `None`.
     */
    def toOption = if (isEmpty) None else Some(str)
}

private[sbt] class XElem(val elem: XMLElem)
{
    private def doAdd(name: String, value: String): XElem =
        new XElem(elem %
                  new UnprefixedAttribute(name, value,
                                          XMLNode.NoAttributes))

    /**
     * Append an unprefixed attribute to an element, returning a
     * new element. If the attribute value is empty, it's not added.
     *
     * @param name  the attribute name
     * @param value its value
     *
     * @return a copy of the element, with the attribute added.
     */
    def addAttr(name: String, value: String): XElem =
        if (value.isEmpty) this else doAdd(name, value)

    /**
     * Append an unprefixed attribute to an element, returning a
     * new element. If the attribute value is empty, it's not added.
     *
     * @param name  the attribute name
     * @param value its value, or None
     *
     * @return a copy of the element, with the attribute added.
     */
    def addAttr(name: String, value: Option[String]): XElem =
    {
        value match
        {
            case None =>    this
            case Some(v) => doAdd(name, v)
        }
    }
}

private[sbt] object Implicits
{
    // Implicits

    implicit def stringToWrapper(s: String): XString = new XString(s)
    implicit def wrapperToString(is: XString): String = is.str

    implicit def elemToWrapper(e: XMLElem): XElem = new XElem(e)
    implicit def wrapperToElem(ie: XElem): XMLElem = ie.elem
}

/**
 * Maintains a map of string options.
 */
trait OptionStrings
{
    import Implicits._

    private val options = MutableMap.empty[String, Option[String]]

    protected def setOption(name: String, value: String) =
        options += name -> value.toOption

    protected def getOption(name: String) =
        options.getOrElse(name, None)

    protected def getOptionString(name: String) =
        optionToString(options.getOrElse(name, None))

    protected def optionToString(o: Option[String]) =
        if (o == None) "" else o.get

    protected def stringOptionIsRequired(name: String) =
        options.getOrElse(name, None) match
        {
            case None =>
                throw new MissingFieldException(name)
            case _ =>
        }

    protected def strOptToXMLElement(name: String): XMLNode =
        options.getOrElse(name, None) match
        {
            case None =>
                new XMLComment("No " + name + " element")
            case Some(text) =>
                XMLElem(null, name, XMLNode.NoAttributes, XMLTopScope,
                        XMLText(text))
        }
}

/**
 * Trait for a section, containing common stuff.
 */
trait Section
{
    val SectionName: String

    import Implicits._

    /**
     * XML for the section
     */
    protected def sectionToXML: XMLElem

    /**
     * Contains any custom XML for the section. Typically supplied
     * by the writer of the build script.
     */
    var customXML: XMLNodeSeq = XMLNodeSeq.Empty

    /**
     * Generate the section's XML. Calls out to `sectionToXML`
     * and uses the contents of `customXML`.
     *
     * @return An XML element
     */
    def toXML: XMLElem =
     {
         // Append any custom XML to the end, as a series of child
         // elements.
         val node = sectionToXML
         val custom = customXML
         val allChildren = node.child ++ custom
         XMLElem(node.prefix, node.label, node.attributes, node.scope,
                 allChildren: _*)
     }

    /**
     * Writes a string to a file, overwriting the file.
     *
     * @param path  the SBT path for the file to be written
     * @param str   the string to write
     */
    protected def writeStringToFile(path: RichFile, str: String): Unit =
        writeStringToFile(path.absolutePath, str)

    /**
     * Writes a string to a file, overwriting the file.
     *
     * @param path  the path of the file to be written
     * @param str   the string to write
     */
    protected def writeStringToFile(path: String, str: String): Unit =
    {
        import java.io.FileWriter
        val writer = new FileWriter(path)
        writer.write(str + "\n")
        writer.flush
        writer.close
    }

    /**
     * Create an empty XML node IFF a boolean flag is set. Otherwise,
     * return an XML comment.
     *
     * @param name   the XML element name
     * @param create the flag to test; if `true`, an XML element is created
     *
     * @return An XML node, either a node of the specified name or a comment
     */
    protected def maybeXML(name: String, create: Boolean): XMLNode =
        maybeXML(name, create, Map.empty[String,String])

    /**
     * Create an empty XML node IFF a boolean flag is set. Otherwise,
     * return an XML comment.
     *
     * @param name   the XML element name
     * @param create the flag to test; if `true`, an XML element is created
     * @param attrs  name/value pairs for the XML attributes to attach
     *               to the element. An empty map signifies no attributes
     *
     * @return An XML node, either a node of the specified name or a comment
     */
    protected def maybeXML(name: String,
                           create: Boolean,
                           attrs: Map[String, String]): XMLNode =
    {
        def makeAttrs(attrs: List[(String, String)]): XMLMetaData =
        {
            attrs match
            {
                case (n, v) :: Nil =>
                    new UnprefixedAttribute(n, v, XMLNode.NoAttributes)
                case (n, v) :: tail =>
                    new UnprefixedAttribute(n, v, makeAttrs(tail))
                case Nil =>
                    XMLNull
            }
        }

        if (! create)
            new XMLComment("No " + name + " element")

        else
            {
                XMLElem(null, name, makeAttrs(attrs.iterator.toList),
                        XMLTopScope, XMLText(""))
            }
    }

    /**
     * Convert a boolean value to a "yes" or "no" string
     */
    protected def yesno(b: Boolean): String = if (b) "yes" else "no"
}

// ------------------------------------------------------------------------
// Main Config Class
// ------------------------------------------------------------------------

/**
 * Base configuration class.
 *
 * @param workingInstallDir  the directory to use to create the
 *                           actual installation XML file(s)
 * @param streams            SBT TaskStreams object
 */
abstract class IzPackConfigBase extends Section
{
    final val SectionName = "IzPackConfig"

    final val workingInstallDir = (new RichFile(new File(".")) / 
                                   "target" / "izpack").absolutePath

    object ParseType extends Enumeration
    {
        type ParseType = Value

        val JavaProperties = Value("javaprop")
        val XML = Value("xml")
        val Plain = Value("plain")
        val Java = Value("Java")
        val Shell = Value("Shell")
        val Ant = Value("Ant")
    }

    final val Windows = "windows"
    final val Darwin  = "macosx"
    final val MacOS   = Darwin
    final val MacOSX  = Darwin
    final val Unix    = "unix"
    final val OS = "os"
    final val Id = "id"

    private var theInfo: Option[Info] = None
    var languages: List[String] = Nil
    private var theResources: Option[Resources] = None
    private var thePackaging: Option[Packaging] = None
    private var theVariables: Option[Variables] = None
    private var theGuiPrefs: Option[GuiPrefs] = None
    private var thePanels: Option[Panels] = None
    private var thePacks: Option[Packs] = None

    private lazy val dateFormatter =
        new java.text.SimpleDateFormat("yyyy/MM/dd HH:mm:ss")

    private def setOnlyOnce[T <: Section](target: Option[T],
                                          newValue: Option[T]): Option[T] =
    {
        (target, newValue) match
        {
            case (Some(t), None) =>
                None

            case (None, None) =>
                None

            case (Some(t), Some(v)) =>
                throw new IzPluginException("Only one " + t.SectionName +
                                            " object is allowed.")

            case (None, Some(v)) =>
                newValue
        }
    }

    def info_=(i: Option[Info]): Unit =
        theInfo = setOnlyOnce(theInfo, i)
    def info: Option[Info] = theInfo

    def panels_=(p: Option[Panels]): Unit =
        thePanels = setOnlyOnce(thePanels, p)
    def panels: Option[Panels] = thePanels

    def resources_=(r: Option[Resources]): Unit =
        theResources = setOnlyOnce(theResources, r)
    def resources: Option[Resources] = theResources

    def packaging_=(p: Option[Packaging]): Unit =
        thePackaging = setOnlyOnce(thePackaging, p)
    def packaging: Option[Packaging] = thePackaging

    def variables_=(p: Option[Variables]): Unit =
        theVariables = setOnlyOnce(theVariables, p)
    def variables: Option[Variables] = theVariables

    def guiprefs_=(g: Option[GuiPrefs]): Unit =
        theGuiPrefs = setOnlyOnce(theGuiPrefs, g)
    def guiprefs: Option[GuiPrefs] = theGuiPrefs

    def packs_=(p: Option[Packs]): Unit =
        thePacks = setOnlyOnce(thePacks, p)
    def packs: Option[Packs] = thePacks

    private def languagesToXML =
    {
        val langs = if (languages == Nil) List("eng") else languages

        <locale> {languages.map(name => <langpack iso3={name}/>)} </locale>
    }

    protected def sectionToXML =
    {
        val now = dateFormatter.format(new java.util.Date)

        <installation version="1.0">
            {new XMLComment("IzPack installation file.")}
            {new XMLComment("Generated by SBT IzPack plugin: " + now)}
            {optionalSectionToXML(info, "Info")}
            {languagesToXML}
            {optionalSectionToXML(resources, "Resources")}
            {optionalSectionToXML(packaging, "Packaging")}
            {optionalSectionToXML(variables, "Variables")}
            {optionalSectionToXML(guiprefs, "GuiPrefs")}
            {optionalSectionToXML(panels, "Panels")}
            {optionalSectionToXML(packs, "Packs")}
        </installation>
    }

    private def optionalSectionToXML(section: Option[Section], name: String) =
        section match
        {
            case Some(sect) => sect.toXML
            case None       => new XMLComment("No " + name + " section")
        }

    def generateXML(log: Logger): Unit =
    {
        import Path._

        log.debug("Creating " + workingInstallDir)
        new File(workingInstallDir).mkdirs()
        log.debug("Generating " + installXMLPath.absolutePath)
        val xml = toXML
        val prettyPrinter = new XMLPrettyPrinter(256, 2)
        writeStringToFile(installXMLPath, prettyPrinter.format(xml))
        log.info("Done.")
    }

    def installXMLPath: RichFile = workingInstallDir / "install.xml"

    private def relPath(s: String): RichFile = workingInstallDir / s

    // -----------------------------------------------------------------------
    // <info>
    // -----------------------------------------------------------------------

    private case class Author(val name: String, val email: Option[String])
    {
        override def toString =
            email match
        {
            case None    => name
            case Some(e) => name + " at " + e
        }

        def toXML = <author name={name} email={email.getOrElse("")}/>
    }

    class Info extends Section with OptionStrings
    {
        import Implicits._

        final val SectionName = "Info"

        info = Some(this)

        var createUninstaller = true
        var requiresJDK = false
        var runPrivileged = false
        var pack200 = false
        var writeInstallationInfo = true

        private val authors = new ListBuffer[Author]

        private val JavaVersion = "javaversion"
        private val URL = "url"
        private val AppSubpath = "appsubpath"
        private val WebDir = "webdir"
        private val AppName = "appname"
        private val AppVersion = "appversion"
        private val SummaryLogFilePath = "summarylogfilepath"

        setOption(JavaVersion, "1.6")

        def author(name: String): Unit =
            author(name, "")
        def author(name: String, email: String): Unit =
            authors += Author(name, email.toOption)

        def summaryLogFilePath_=(s: String): Unit =
            setOption(SummaryLogFilePath, s)
        def summaryLogFilePath: String = getOptionString(SummaryLogFilePath)

        def url_=(u: String): Unit = setOption(URL, u)
        def url: String = getOptionString(URL)

        def javaVersion_=(v: String): Unit = setOption(JavaVersion, v)
        def javaVersion: String = getOptionString(JavaVersion)

        def appName_=(v: String): Unit = setOption(AppName, v)
        def appName: String = getOptionString(AppName)

        def appVersion_=(v: String): Unit = setOption(AppVersion, v)
        def appVersion: String = getOptionString(AppVersion)

        def appSubPath_=(v: String): Unit = setOption(AppSubpath, v)
        def appSubPath: String = getOptionString(AppSubpath)

        def webdir_=(v: String): Unit = setOption(WebDir, v)
        def webdir: String = getOptionString(WebDir)

        protected def sectionToXML =
        {
            stringOptionIsRequired(AppName)

            <info>
                <appname>{appName}</appname>
                {strOptToXMLElement(AppVersion)}
                {strOptToXMLElement(JavaVersion)}
                {strOptToXMLElement(AppSubpath)}
                {strOptToXMLElement(URL)}
                {strOptToXMLElement(WebDir)}
                {strOptToXMLElement(SummaryLogFilePath)}
                <writeinstallationinformation>
                    {yesno(writeInstallationInfo)}
                </writeinstallationinformation>
                <requiresjdk>{yesno(requiresJDK)}</requiresjdk>
                {maybeXML("uninstaller", createUninstaller,
                          Map("write" -> "yes"))}
                {maybeXML("run-privileged", runPrivileged)}
                {maybeXML("pack200", pack200)}
                {authorsToXML}
            </info>
        }

        private def authorsToXML =
        {
            if (authors.size > 0)
                <authors> {authors.map(_.toXML)} </authors>
            else
                new XMLComment("no authors")
        }
    }

    // ----------------------------------------------------------------------
    // <resources>
    // ----------------------------------------------------------------------

    class Resources extends Section
    {
        final val SectionName = "Resources"

        resources = Some(this)

        private var installDirectory = new InstallDirectory
        private var individualResources = new ListBuffer[Resource]

        class Resource extends Section with OptionStrings
        {
            final val SectionName = "Resource"

            individualResources += this

            private var srcOption: Option[RichFile] = None

            import ParseType._

            var parse: Boolean = false
            val parseType: ParseType = ParseType.Plain

            def id_=(s: String): Unit = setOption(Id, s)
            def id: String = getOptionString(Id)

            def source_=(p: RichFile): Unit = srcOption = Some(p)
            def source: RichFile = srcOption.getOrElse(relPath("."))

            protected def sectionToXML =
            {
                stringOptionIsRequired(Id)
                if (srcOption == None)
                    throw new MissingFieldException("source")

                val idString = getOptionString(Id)

                <res id={idString} src={srcOption.get.absolutePath}
                     parse={yesno(parse)} type={parseType.toString}/>
            }
        }

        class InstallDirectory
        {
            installDirectory = this

            implicit def stringToInstallDirectory(s: String) =
                new InstallDirectoryBuilder(s)

            val dirs = MutableMap.empty[String,String]
            var customXML: XMLNodeSeq = XMLNodeSeq.Empty

            class InstallDirectoryBuilder(val path: String)
            {
                def on(operatingSystem: String): Unit =
                    dirs += operatingSystem -> path
            }

            def toXML =
            {
                val nodes =
                {
                    for ((os, path) <- dirs)
                        yield <res id={"TargetPanel.dir." + os}
                                  src={installDirString(os, path)}/>
                }.toList

                XMLNodeSeq.fromSeq(nodes ++ customXML)
            }

            private def installDirString(os: String, path: String): String =
            {
                val filename = "instdir_" + os + ".txt"
                val fullPath = workingInstallDir / filename
                // Put the string in the file. That's how IzPack wants it.
                writeStringToFile(fullPath, path)
                fullPath.absolutePath
            }
        }

        protected def sectionToXML =
            <resources> {individualResources.map(_.toXML)} </resources>
    }

    // ----------------------------------------------------------------------
    // <variables
    // ----------------------------------------------------------------------

    /**
     * Defines variables that will be set in the generated XML and
     * can be substituted in Parsable files.
     */
    class Variables extends Section
    {
        final val SectionName = "Variables"

        variables = Some(this)

        private val variableSettings = MutableMap.empty[String,String]

        def variable(name: String, value: Any): Unit =
            variableSettings += name -> value.toString

        protected def sectionToXML =
        {
            <variables>
            {
                if (variableSettings.size == 0)
                    new XMLComment("no variables")
                else
                    for ((n, v) <- variableSettings)
                    yield <variable name={n} value={v}/>
            }
            </variables>
        }
    }

    // ----------------------------------------------------------------------
    // <packaging>
    // ----------------------------------------------------------------------

    class Packaging extends Section
    {
        final val SectionName = "Packaging"

        packaging = Some(this)

        object Packager extends Enumeration
        {
            private val packageName = "com.izforge.izpack.compiler"
            type Packager = Value

            val SingleVolume = Value(packageName + ".Packager")
            val MultiVolume = Value(packageName + ".MultiVolumePackager")
        }

        import Packager._

        var packager: Packager = SingleVolume
        var volumeSize: Int = 0
        var firstVolFreeSpace: Int = 0

        protected def sectionToXML =
        {
            if ((packager != MultiVolume) &&
                ((volumeSize + firstVolFreeSpace) > 0))
            {
                error("volumeSize and firstVolFreeSpace are " +
                      "ignored unless packager is MultiVolume.")
            }

            var unpacker: String = ""

            <packaging>
                <packager class={packager.toString}>
                {
                    packager match
                    {
                        case MultiVolume =>
                            <options volumesize={volumeSize.toString}
                                     firstvolumefreespace={firstVolFreeSpace.toString}
                             />
                            unpacker = "com.izforge.izpack.installer." +
                                       "MultiVolumeUnpacker"
                        case SingleVolume =>
                            new XMLComment("no options")
                            unpacker = "com.izforge.izpack.installer.Unpacker"
                    }
                }
                </packager>
                <unpacker class={unpacker}/>
            </packaging>
        }
    }

    // ----------------------------------------------------------------------
    // <guiprefs>
    // ----------------------------------------------------------------------

    class GuiPrefs extends Section
    {
        final val SectionName = "GuiPrefs"

        guiprefs = Some(this)

        var height: Int = 600
        var width: Int = 800
        var resizable: Boolean = true

        private var lafs = new ListBuffer[LookAndFeel]

        class LookAndFeel(val name: String)
        extends Section with OperatingSystemConstraints
        {
            final val SectionName = "LookAndFeel"

            lafs += this

            var params = Map.empty[String, String]

            protected def sectionToXML =
            {
                <laf name={name}> {operatingSystemsToXML} </laf>
            }
        }

        protected def sectionToXML =
        {
            val strResizable = if (resizable) "yes" else "no"

            <guiprefs height={height.toString} width={width.toString}
                      resizable={yesno(resizable)}>
                {lafs.map(_.toXML)}
            </guiprefs>
        }
    }

    // ----------------------------------------------------------------------
    // <panels>
    // ----------------------------------------------------------------------

    class Panels extends Section
    {
        final val SectionName = "Panels"

        panels = Some(this)

        final val Panel = "panel"

        private val panelClasses = new ListBuffer[Panel]

        /**
         * A single panel.
         */
        class Panel(val name: String) extends Section with OptionStrings
        {
            import Implicits._

            final val SectionName = "Panel"

            final val Jar = "jar"
            final val Condition = "condition"

            var jarOption: Option[RichFile] = None
            var help: Map[String, RichFile] = Map.empty[String, RichFile]

            private var actions = new ListBuffer[Action]
            private var validators = new ListBuffer[Validator]

            def id_=(s: String): Unit = setOption(Id, s)
            def id: String = getOptionString(Id)

            def condition_=(s: String): Unit = setOption(condition, s)
            def condition: String = getOptionString(condition)

            panelClasses += this

            /**
             * Validators
             */
            class Validator(val classname: String)
            {
                validators += this

                def toXML = <validator classname={classname}/>
            }

            /**
             * Embedded actions.
             */
            class Action(val stage: String, val classname: String)
            {
                actions += this

                def toXML = <action stage={stage} classname={classname}/>
            }

            /**
             * Allows assignment of `jar` field
             */
            def jar_=(p: RichFile): Unit = jarOption = Some(p)
            def jar: RichFile = jarOption.getOrElse(relPath("nothing.jar"))

            protected def sectionToXML =
            {
                val jarAttr =
                {
                    jarOption match
                    {
                        case None    => ""
                        case Some(p) => p.absolutePath
                    }
                }

                val elem =
                <panel classname={name}>
                {
                    if (help.size > 0)
                    {
                        for ((lang, path) <- help)
                            yield <help iso3={lang} src={path.absolutePath}/>
                    }
                    else
                        new XMLComment("no help")
                }
                {
                    if (validators.size > 0)
                        validators.map(_.toXML)
                    else
                        new XMLComment("no validators")
                }
                {
                    if (actions.size > 0)
                        <actions>{actions.map(_.toXML)}</actions>
                    else
                        new XMLComment("no actions")
                }
                </panel>

                elem.addAttr("jar", jarAttr)
                    .addAttr("id", getOption(Id))
                    .addAttr("condition", getOption(Condition))
            }
        }

        protected def sectionToXML =
            <panels> {panelClasses.map(_.toXML)} </panels>
    }

    // ----------------------------------------------------------------------
    // <packs>
    // ----------------------------------------------------------------------

    class Packs extends Section
    {
        final val SectionName = "Packs"

        packs = Some(this)

        private var individualPacks = new ListBuffer[Pack]

        class Pack(val name: String)
        extends Section with OperatingSystemConstraints
        {
            final val SectionName = "Pack"

            individualPacks += this

            var required = false
            var preselected = false
            var hidden = false
            var depends: List[Pack] = Nil
            var description = name

            private var files = new ListBuffer[OneFile]
            private var filesets = new ListBuffer[FileSet]
            private var executables = new ListBuffer[Executable]
            private var parsables = new ListBuffer[Parsable]

            trait OneFile extends Section with OperatingSystemConstraints
            {
                files += this
            }

            object Overwrite extends Enumeration
            {
                type Overwrite = Value

                val Yes = Value("true")
                val No = Value("false")
                val AskYes = Value("asktrue")
                val AskNo = Value("askfalse")
                val Update = Value("update")
            }

            class SingleFile(val source: RichFile, val target: String)
            extends OneFile
            {
                final val SectionName = "SingleFile"

                protected def sectionToXML =
                {
                    <singlefile src={source.absolutePath}
                        target={target}>
                        {operatingSystemsToXML}
                    </singlefile>
                }
            }

            class File(val source: RichFile, val targetdir: String)
            extends OneFile
            {
                final val SectionName = "File"

                var unpack = false
                var overwrite = Overwrite.Update

                protected def sectionToXML =
                {
                    <file src={source.absolutePath}
                        targetdir={targetdir}
                        unpack={yesno(unpack)}
                        override={overwrite.toString}>
                        {operatingSystemsToXML}
                    </file>
                }
            }

            class FileSet(val files: PathFinder, val targetdir: String)
            extends OperatingSystemConstraints
            {
                final val SectionName = "FileSet"

                var overwrite = Overwrite.Update
                var unpack = false

                filesets += this

                def toXML =
                {
                    val nodes = files.get.map(path => fileToXML(path))
                    XMLNodeSeq.fromSeq(nodes.toList)
                }

                private def fileToXML(path: RichFile) =
                {
                    <file src={path.absolutePath}
                          targetdir={targetdir}
                          unpack={yesno(unpack)}
                          override={overwrite.toString}>
                        {operatingSystemsToXML}
                    </file>
                }
            }

            class Executable(val target: String, val stage: String)
            extends Section with OperatingSystemConstraints
            {
                import Implicits._

                final val SectionName = "Executable"

                object FailureType extends Enumeration
                {
                    type FailureType = Value

                    val Abort = Value("abort")
                    val Ask = Value("ask")
                    val Warn = Value("warn")
                }

                executables += this

                import FailureType._

                val args: List[String] = Nil
                val keep = true
                val failure = FailureType.Ask

                def this(target: String) = this(target, "never")

                protected def sectionToXML =
                {
                    <executable targetfile={target} stage={stage}
                                keep={keep.toString}
                                failure={failure.toString}>
                        {operatingSystemsToXML}
                    {
                        if (args.length == 0)
                            new XMLComment("No args")
                        else
                            args.map(a => <arg value={a}/>)
                    }
                    </executable>
                }
            }

            class Parsable(val target: String)
            extends Section with OperatingSystemConstraints
            {
                import Implicits._

                final val SectionName = "Parsable"

                parsables += this

                import ParseType._

                val parseType: ParseType = ParseType.Plain

                protected def sectionToXML =
                {
                    <parsable targetfile={target} type={parseType.toString}>
                        {operatingSystemsToXML}
                    </parsable>
                }
            }

            protected def sectionToXML =
            {
                <pack name={name}
                      required={yesno(required)}
                      hidden={yesno(hidden)}
                      preselected={yesno(preselected)}>
                    <description>{description}</description>
                    {operatingSystemsToXML}
                    {depends.map(dep => <depends packname={dep.name}/>)}
                    {files.map(_.toXML)}
                    {filesets.map(_.toXML)}
                    {parsables.map(_.toXML)}
                    {executables.map(_.toXML)}
                </pack>
            }
        }

        protected def sectionToXML =
            <packs> {individualPacks.map(_.toXML)} </packs>
    }
}

// Some useful constants
object Constants
{
    val EmptyConfig = new IzPackConfigBase {}
}
