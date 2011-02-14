/*
 * Copyright 2005-2010 LAMP/EPFL
 */
// $Id$

package scala.tools.eclipse

import java.util.ResourceBundle
import org.eclipse.core.runtime.{ IAdaptable, IProgressMonitor }
import org.eclipse.jdt.core.IJavaElement
import org.eclipse.jdt.core.dom.CompilationUnit
import org.eclipse.jdt.internal.ui.javaeditor.CompilationUnitEditor
import org.eclipse.jdt.internal.ui.javaeditor.selectionactions._
import org.eclipse.jdt.internal.ui.search.IOccurrencesFinder._
import org.eclipse.jdt.ui.actions.IJavaEditorActionDefinitionIds
import org.eclipse.jdt.ui.text.JavaSourceViewerConfiguration
import org.eclipse.jface.preference.IPreferenceStore
import org.eclipse.jface.text._
import org.eclipse.jface.text.source.{ Annotation, IAnnotationModelExtension, SourceViewerConfiguration }
import org.eclipse.jface.viewers.ISelection
import org.eclipse.ui.{ IWorkbenchPart, ISelectionListener, IFileEditorInput }
import org.eclipse.ui.editors.text.{ ForwardingDocumentProvider, TextFileDocumentProvider }
import org.eclipse.ui.texteditor.{ IAbstractTextEditorHelpContextIds, ITextEditorActionConstants, IWorkbenchActionDefinitionIds, TextOperationAction }
import scala.collection.JavaConversions._
import scala.collection.mutable
import scala.tools.eclipse.javaelements.ScalaSourceFile
import scala.tools.eclipse.markoccurrences.{ ScalaOccurrencesFinder, Occurrences }

class ScalaSourceFileEditor extends CompilationUnitEditor with ScalaEditor {

  import ScalaSourceFileEditor._

  setPartName("Scala Editor")

  override protected def createActions() {
    super.createActions()

    val cutAction = new TextOperationAction(bundleForConstructedKeys, "Editor.Cut.", this, ITextOperationTarget.CUT) //$NON-NLS-1$
    cutAction.setHelpContextId(IAbstractTextEditorHelpContextIds.CUT_ACTION)
    cutAction.setActionDefinitionId(IWorkbenchActionDefinitionIds.CUT)
    setAction(ITextEditorActionConstants.CUT, cutAction)

    val copyAction = new TextOperationAction(bundleForConstructedKeys, "Editor.Copy.", this, ITextOperationTarget.COPY, true) //$NON-NLS-1$
    copyAction.setHelpContextId(IAbstractTextEditorHelpContextIds.COPY_ACTION)
    copyAction.setActionDefinitionId(IWorkbenchActionDefinitionIds.COPY)
    setAction(ITextEditorActionConstants.COPY, copyAction)

    val pasteAction = new TextOperationAction(bundleForConstructedKeys, "Editor.Paste.", this, ITextOperationTarget.PASTE) //$NON-NLS-1$
    pasteAction.setHelpContextId(IAbstractTextEditorHelpContextIds.PASTE_ACTION)
    pasteAction.setActionDefinitionId(IWorkbenchActionDefinitionIds.PASTE)
    setAction(ITextEditorActionConstants.PASTE, pasteAction)

    val selectionHistory = new SelectionHistory(this)

    val historyAction = new StructureSelectHistoryAction(this, selectionHistory)
    historyAction.setActionDefinitionId(IJavaEditorActionDefinitionIds.SELECT_LAST)
    setAction(StructureSelectionAction.HISTORY, historyAction)
    selectionHistory.setHistoryAction(historyAction)

    val selectEnclosingAction = new ScalaStructureSelectEnclosingAction(this, selectionHistory)
    selectEnclosingAction.setActionDefinitionId(IJavaEditorActionDefinitionIds.SELECT_ENCLOSING)
    setAction(StructureSelectionAction.ENCLOSING, selectEnclosingAction)

  }

  override protected def initializeKeyBindingScopes() {
    setKeyBindingScopes(Array(SCALA_EDITOR_SCOPE))
  }

  override def createJavaSourceViewerConfiguration: JavaSourceViewerConfiguration =
    new ScalaSourceViewerConfiguration(getPreferenceStore, this)

  override def setSourceViewerConfiguration(configuration: SourceViewerConfiguration) {
    super.setSourceViewerConfiguration(
      configuration match {
        case svc: ScalaSourceViewerConfiguration => svc
        case _ => new ScalaSourceViewerConfiguration(getPreferenceStore, this)
      })
  }

  private[eclipse] def sourceViewer = getSourceViewer

  private var occurrenceAnnotations: Array[Annotation] = _
  
  override def updateOccurrenceAnnotations(selection: ITextSelection, astRoot: CompilationUnit) {
	askForOccurrencesUpdate(selection, astRoot)
	super.updateOccurrenceAnnotations(selection, astRoot)
  }
  
  private def performOccurrencesUpdate(selection: ITextSelection, astRoot: CompilationUnit) {
    import ScalaPlugin.{plugin => thePlugin }
    
    val documentProvider = getDocumentProvider
    if (documentProvider eq null)
      return

    //  TODO: find out why this code does a cast to IAdaptable before calling getAdapter 
    val adaptable = getEditorInput.asInstanceOf[IAdaptable].getAdapter(classOf[IJavaElement])
    // println("adaptable: " + adaptable.getClass + " : " + adaptable.toString)
      
    adaptable match {
      case scalaSourceFile: ScalaSourceFile =>
        val annotations = getAnnotations(selection, scalaSourceFile)
        val annotationModel = documentProvider.getAnnotationModel(getEditorInput)
        if (annotationModel eq null)
          return
        annotationModel.asInstanceOf[ISynchronizable].getLockObject() synchronized {
          val annotationModelExtension = annotationModel.asInstanceOf[IAnnotationModelExtension]
          annotationModelExtension.replaceAnnotations(occurrenceAnnotations, annotations)
          occurrenceAnnotations = annotations.keySet.toArray
        }
        
      case _ =>
        // TODO: pop up a dialog explaining what needs to be fixed or fix it ourselves
        thePlugin checkOrElse (adaptable.asInstanceOf[ScalaSourceFile], // trigger the exception, so as to get a diagnostic stack trace 
          "Could not recompute occurrence annotations: configuration problem")
    }
  }
  
  private def getAnnotations(selection: ITextSelection, scalaSourceFile: ScalaSourceFile): mutable.Map[Annotation, Position] = {
    val annotations = for {
      Occurrences(name, locations) <- new ScalaOccurrencesFinder(scalaSourceFile, selection.getOffset, selection.getLength).findOccurrences.toList
      location <- locations
      val offset = location.getOffset
      val length = location.getLength
      val position = new Position(location.getOffset, location.getLength)
    } yield new Annotation(OCCURRENCE_ANNOTATION, false, "Occurrence of '" + name + "'") -> position
    mutable.Map(annotations: _*)
  }
  
  def askForOccurrencesUpdate(selection: ITextSelection, astRoot: CompilationUnit) {
    import org.eclipse.core.runtime.jobs.Job
    import org.eclipse.core.runtime.IProgressMonitor
    import org.eclipse.core.runtime.{IStatus, Status}
    
    val job = new Job("updateOccurrenceAnnotations"){
      def run(monitor : IProgressMonitor) : IStatus = {
        performOccurrencesUpdate(selection, astRoot)
        Status.OK_STATUS
      }
    }
  
    job.setPriority(Job.INTERACTIVE)
    job.schedule()
  }


  override def doSelectionChanged(selection: ISelection) {
    super.doSelectionChanged(selection)
    val selectionProvider = getSelectionProvider
    if (selectionProvider != null)
      selectionProvider.getSelection match {
        case textSel: ITextSelection => askForOccurrencesUpdate(textSel, null)
        case _ =>
      }
  }

  lazy val selectionListener = new ISelectionListener() {
    def selectionChanged(part: IWorkbenchPart, selection: ISelection) {
      selection match {
    	  case textSel : ITextSelection => askForOccurrencesUpdate(textSel, null)
    	  case _ =>
      }
    }
  }
  
  override def installOccurrencesFinder(forceUpdate: Boolean) {
    super.installOccurrencesFinder(forceUpdate)
    getEditorSite.getPage.addPostSelectionListener(selectionListener)
  }
  
  override def uninstallOccurrencesFinder() {
    getEditorSite.getPage.removePostSelectionListener(selectionListener)
    super.uninstallOccurrencesFinder
  }
}

object ScalaSourceFileEditor {

  private val EDITOR_BUNDLE_FOR_CONSTRUCTED_KEYS = "org.eclipse.ui.texteditor.ConstructedEditorMessages"
  private val bundleForConstructedKeys = ResourceBundle.getBundle(EDITOR_BUNDLE_FOR_CONSTRUCTED_KEYS)

  private val SCALA_EDITOR_SCOPE = "scala.tools.eclipse.scalaEditorScope"

  private val OCCURRENCE_ANNOTATION = "org.eclipse.jdt.ui.occurrences"
}