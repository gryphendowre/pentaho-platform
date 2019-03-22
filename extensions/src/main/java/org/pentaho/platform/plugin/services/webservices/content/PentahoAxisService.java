package org.pentaho.platform.plugin.services.webservices.content;

import org.apache.axiom.om.OMElement;
import org.apache.axis2.AxisFault;
import org.apache.axis2.Constants;
import org.apache.axis2.deployment.util.PhasesInfo;
import org.apache.axis2.deployment.util.Utils;
import org.apache.axis2.description.AxisOperation;
import org.apache.axis2.description.AxisService;
import org.apache.axis2.description.Parameter;
import org.apache.axis2.description.WSDL2Constants;
import org.apache.axis2.description.java2wsdl.DocLitBareSchemaGenerator;
import org.apache.axis2.description.java2wsdl.Java2WSDLConstants;
import org.apache.axis2.description.java2wsdl.SchemaGenerator;
import org.apache.axis2.engine.AxisConfiguration;
import org.apache.axis2.engine.MessageReceiver;
import org.apache.axis2.util.Loader;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.ws.commons.schema.utils.NamespaceMap;

import javax.xml.namespace.QName;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

/**
 * Class AxisService
 */
public class PentahoAxisService extends AxisService {

  private static final Log log = LogFactory.getLog( PentahoAxisService.class );

  public PentahoAxisService() {
    super();
  }
  public PentahoAxisService( String name ) {
    super( name );
  }

  /**
   * To create an AxisService using given service impl class name first
   * generate schema corresponding to the given java class , next for each
   * methods AxisOperation will be created. If the method is in-out it will
   * uses RPCMessageReceiver else RPCInOnlyMessageReceiver <p/> Note : Inorder
   * to work this properly RPCMessageReceiver should be available in the class
   * path otherewise operation can not continue
   *
   * @param implClass
   *            Service implementation class
   * @param axisConfig
   *            Current AxisConfiguration
   * @return return created AxisSrevice the creted service , it can either be
   *         null or valid service
   */
  public static PentahoAxisService createService( String implClass,
                                          AxisConfiguration axisConfig ) throws AxisFault {

    try {
      HashMap<String, MessageReceiver> messageReciverMap = new HashMap<String, MessageReceiver>();
      Class inOnlyMessageReceiver = Loader
        .loadClass( "org.apache.axis2.rpc.receivers.RPCInOnlyMessageReceiver" );
      MessageReceiver messageReceiver = (MessageReceiver) inOnlyMessageReceiver
        .newInstance();
      messageReciverMap.put( WSDL2Constants.MEP_URI_IN_ONLY,
        messageReceiver );
      Class inoutMessageReceiver = Loader
        .loadClass( "org.apache.axis2.rpc.receivers.RPCMessageReceiver" );
      MessageReceiver inOutmessageReceiver = (MessageReceiver) inoutMessageReceiver
        .newInstance();
      messageReciverMap.put( WSDL2Constants.MEP_URI_IN_OUT,
        inOutmessageReceiver );
      messageReciverMap.put( WSDL2Constants.MEP_URI_ROBUST_IN_ONLY,
        inOutmessageReceiver );

      return createService( implClass, axisConfig, messageReciverMap,
        null, null, axisConfig.getSystemClassLoader() );
    } catch ( Exception e ) {
      throw AxisFault.makeFault( e );
    }
  }

  /**
   * messageReceiverClassMap will hold the MessageReceivers for given meps.
   * Key will be the mep and value will be the instance of the MessageReceiver
   * class. Ex: Map mrMap = new HashMap();
   * mrMap.put( "http://www.w3.org/ns/wsdl/in-only",
   * RPCInOnlyMessageReceiver.class.newInstance() );
   * mrMap.put( "http://www.w3.org/ns/wsdl/in-out",
   * RPCMessageReceiver.class.newInstance() );
   *
   * @param implClass
   * @param axisConfiguration
   * @param messageReceiverClassMap
   * @param targetNamespace
   * @param schemaNamespace
   * @throws AxisFault
   */
  public static PentahoAxisService createService( String implClass,
                                          AxisConfiguration axisConfiguration, Map messageReceiverClassMap,
                                          String targetNamespace, String schemaNamespace, ClassLoader loader )
    throws AxisFault {
    int index = implClass.lastIndexOf( "." );
    String serviceName;
    if ( index > 0 ) {
      serviceName = implClass.substring( index + 1, implClass.length() );
    } else {
      serviceName = implClass;
    }

    SchemaGenerator schemaGenerator;
    ArrayList excludeOpeartion = new ArrayList();
    PentahoAxisService service = new PentahoAxisService();
    service.setParent( axisConfiguration );
    service.setName( serviceName );

    try {
      Parameter generateBare = service
        .getParameter( Java2WSDLConstants.DOC_LIT_BARE_PARAMETER );
      if ( generateBare != null && "true".equals( generateBare.getValue() ) ) {
        schemaGenerator = new DocLitBareSchemaGenerator( loader,
          implClass, schemaNamespace,
          Java2WSDLConstants.SCHEMA_NAMESPACE_PRFIX, service );
      } else {
        schemaGenerator = new PentahoDefaultSchemaGenerator( loader, implClass,
          schemaNamespace,
          Java2WSDLConstants.SCHEMA_NAMESPACE_PRFIX, service );
      }
      schemaGenerator
        .setElementFormDefault( Java2WSDLConstants.FORM_DEFAULT_UNQUALIFIED );
      Utils.addExcludeMethods( excludeOpeartion );
      schemaGenerator.setExcludeMethods( excludeOpeartion );
    } catch ( Exception e ) {
      throw AxisFault.makeFault( e );
    }

    return createService( implClass, serviceName, axisConfiguration,
      messageReceiverClassMap, targetNamespace, loader,
      schemaGenerator, service );
  }

  /**
   * messageReceiverClassMap will hold the MessageReceivers for given meps.
   * Key will be the mep and value will be the instance of the MessageReceiver
   * class. Ex: Map mrMap = new HashMap();
   * mrMap.put( "http://www.w3.org/ns/wsdl/in-only",
   * RPCInOnlyMessageReceiver.class.newInstance() );
   * mrMap.put( "http://www.w3.org/ns/wsdl/in-out",
   * RPCMessageReceiver.class.newInstance() );
   *
   * @param implClass
   * @param axisConfiguration
   * @param messageReceiverClassMap
   * @param targetNamespace
   * @throws AxisFault
   */
  public static PentahoAxisService createService( String implClass,
                                          String serviceName, AxisConfiguration axisConfiguration,
                                          Map<String, MessageReceiver> messageReceiverClassMap, String targetNamespace,
                                          ClassLoader loader, SchemaGenerator schemaGenerator,
                                          PentahoAxisService axisService ) throws AxisFault {
    Parameter parameter = new Parameter( Constants.SERVICE_CLASS, implClass );
    OMElement paraElement = Utils.getParameter( Constants.SERVICE_CLASS,
      implClass, false );
    parameter.setParameterElement( paraElement );
    axisService.setUseDefaultChains( false );
    axisService.addParameter( parameter );
    axisService.setName( serviceName );
    axisService.setClassLoader( loader );

    NamespaceMap map = new NamespaceMap();
    map.put( Java2WSDLConstants.AXIS2_NAMESPACE_PREFIX,
      Java2WSDLConstants.AXIS2_XSD );
    map.put( Java2WSDLConstants.DEFAULT_SCHEMA_NAMESPACE_PREFIX,
      Java2WSDLConstants.URI_2001_SCHEMA_XSD );
    axisService.setNamespaceMap( map );
    Utils.processBeanPropertyExclude( axisService );
    axisService.setElementFormDefault( false );
    try {
      axisService.addSchema( schemaGenerator.generateSchema() );
    } catch ( Exception e ) {
      throw AxisFault.makeFault( e );
    }
    axisService.setSchemaTargetNamespace( schemaGenerator
      .getSchemaTargetNameSpace() );
    axisService.setTypeTable( schemaGenerator.getTypeTable() );
    if ( targetNamespace == null ) {
      targetNamespace = schemaGenerator.getSchemaTargetNameSpace();
    }
    if ( targetNamespace != null && !"".equals( targetNamespace ) ) {
      axisService.setTargetNamespace( targetNamespace );
    }
    Method[] method = schemaGenerator.getMethods();
    PhasesInfo pinfo = axisConfiguration.getPhasesInfo();
    for ( int i = 0; i < method.length; i++ ) {
      Method jmethod = method[i];

      String methodName = jmethod.getName();

      AxisOperation operation = axisService.getOperation( new QName( methodName ) );

      String mep = operation.getMessageExchangePattern();
      MessageReceiver mr;
      if ( messageReceiverClassMap != null ) {

        if ( messageReceiverClassMap.get( mep ) != null ) {
          Object obj = messageReceiverClassMap.get( mep );
          if ( obj instanceof MessageReceiver ) {
            mr = (MessageReceiver) obj;
            operation.setMessageReceiver( mr );
          } else {
            log
              .error( "Object is not an instance of MessageReceiver, thus, default MessageReceiver has been set" );
            mr = axisConfiguration.getMessageReceiver( operation
              .getMessageExchangePattern() );
            operation.setMessageReceiver( mr );
          }
        } else {
          log
            .error( "Required MessageReceiver couldn't be found, thus, default MessageReceiver has been used" );
          mr = axisConfiguration.getMessageReceiver( operation
            .getMessageExchangePattern() );
          operation.setMessageReceiver( mr );
        }
      } else {
        log
          .error( "MessageRecevierClassMap couldn't be found, thus, default MessageReceiver has been used" );
        mr = axisConfiguration.getMessageReceiver( operation
          .getMessageExchangePattern() );
        operation.setMessageReceiver( mr );
      }
      pinfo.setOperationPhases( operation );
      axisService.addOperation( operation );
    }

    String endpointName = axisService.getEndpointName();
    if ( ( endpointName == null || endpointName.length() == 0 )
      && axisService.getAxisConfiguration() != null ) {
      Utils.addEndpointsToService( axisService, axisService.getAxisConfiguration() );
    }

    return axisService;

  }
}
