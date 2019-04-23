package org.pentaho.platform.plugin.services.webservices.content;

import org.apache.axis2.AxisFault;
import org.apache.axis2.Constants;
import org.apache.axis2.deployment.util.BeanExcludeInfo;
import org.apache.axis2.deployment.util.Utils;
import org.apache.axis2.description.AxisMessage;
import org.apache.axis2.description.AxisOperation;
import org.apache.axis2.description.AxisService;
import org.apache.axis2.description.Parameter;
import org.apache.axis2.description.java2wsdl.DefaultSchemaGenerator;
import org.apache.axis2.description.java2wsdl.Java2WSDLConstants;
import org.apache.axis2.jaxrs.JAXRSModel;
import org.apache.axis2.jaxrs.JAXRSUtils;
import org.apache.axis2.util.JavaUtils;
import org.apache.axis2.wsdl.WSDLConstants;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.ws.commons.schema.XmlSchema;
import org.apache.ws.commons.schema.XmlSchemaComplexContent;
import org.apache.ws.commons.schema.XmlSchemaComplexContentExtension;
import org.apache.ws.commons.schema.XmlSchemaComplexType;
import org.apache.ws.commons.schema.XmlSchemaElement;
import org.apache.ws.commons.schema.XmlSchemaEnumerationFacet;
import org.apache.ws.commons.schema.XmlSchemaImport;
import org.apache.ws.commons.schema.XmlSchemaSequence;
import org.apache.ws.commons.schema.XmlSchemaSimpleType;
import org.apache.ws.commons.schema.XmlSchemaSimpleTypeRestriction;
import org.apache.ws.commons.schema.utils.NamespaceMap;
import org.pentaho.platform.engine.core.system.PentahoSystem;
import org.w3c.dom.Document;

import javax.xml.namespace.QName;
import javax.xml.parsers.DocumentBuilderFactory;
import java.beans.BeanInfo;
import java.beans.Introspector;
import java.beans.PropertyDescriptor;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.lang.annotation.Annotation;
import java.lang.reflect.GenericArrayType;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class PentahoDefaultSchemaGenerator extends DefaultSchemaGenerator {
  private static final Log log = LogFactory.getLog( PentahoDefaultSchemaGenerator.class );
  private JAXRSModel classModel;

  public PentahoDefaultSchemaGenerator( ClassLoader loader, String className, String schematargetNamespace,
                                       String schematargetNamespacePrefix, AxisService service ) throws Exception {
    super( loader, className, schematargetNamespace, schematargetNamespacePrefix, service );
  }

  //This will locad the custom schema file and add that into the schema map
  private void loadCustomSchemaFile() {
    if ( customSchemaLocation != null ) {
      try {
        DocumentBuilderFactory documentBuilderFactory = DocumentBuilderFactory.newInstance();
        documentBuilderFactory.setNamespaceAware( true );
        Document doc = documentBuilderFactory.newDocumentBuilder().parse( new File( customSchemaLocation ) );
        XmlSchema schema = xmlSchemaCollection.read( doc, null );
        schemaMap.put( schema.getTargetNamespace(), schema );
      } catch ( Exception e ) {
        log.info( e.getMessage() );
      }
    }
  }

  /**
   * This will load the mapping file and update the Typetable with the Class name and the Qname
   * Mapping file look like
   * org.foo.bar.FooException|http://www.abc.com/soaframework/common/types|ErrorMessage
   */
  private void loadMappingFile() {
    if ( mappingFileLocation != null ) {
      File file = new File( mappingFileLocation );
      BufferedReader input = null;
      try {
        input = new BufferedReader( new InputStreamReader( new FileInputStream( file ) ) );
        String line;
        while ( ( line = input.readLine() ) != null ) {
          line = line.trim();
          if ( line.length() > 0 && line.charAt( 0 ) != '#' ) {
            String[] values = line.split( "\\|" );
            if ( values != null && values.length > 2 ) {
              typeTable.addComplexSchema( values[0], new QName( values[1], values[2] ) );
            }
          }
        }
      } catch ( IOException ex ) {
        ex.printStackTrace();
      } finally {
        try {
          if ( input != null ) {
            input.close();
          }
        } catch ( IOException ex ) {
          ex.printStackTrace();
        }
      }
    }
  }

  @Override
  protected void generateSchemaforGenericFields( XmlSchema xmlSchema, XmlSchemaSequence sequence, Type genericType,
                                                 String name ) throws Exception {
    try {
      super.generateSchemaforGenericFields( xmlSchema, sequence, genericType, name );
    } catch ( ClassCastException cex ) {
      log.debug( "Generic Type is not a Class type", cex );
    }
  }

  /**
   * Generates schema for all the parameters in method. First generates schema for all different
   * parameter type and later refers to them.
   *
   * @return Returns XmlSchema.
   * @throws Exception
   */
  @Override
  public Collection<XmlSchema> generateSchema() throws Exception {
    loadCustomSchemaFile();
    loadMappingFile();
    //all most all the time the ittr will have only one class in it
    /**
     * Schema genertaion done in two stage 1. Load all the methods and
     * create type for methods parameters ( if the parameters are Bean
     * then it will create Complex types foer those, and if the
     * parameters are simple type which decribe in SimpleTypeTable
     * nothing will happen ) 2. In the next stage for all the methods
     * messages and port types will be creteated
     */
    classModel = JAXRSUtils.getClassModel( serviceClass );
    List<Method> serviceMethods = new ArrayList<>();
    for ( Method method : serviceClass.getMethods() ) {
      if ( method.getDeclaringClass() != Object.class ) {
        serviceMethods.add( method );
      }
    }
    // The order of the methods returned by getMethods is undefined, but the test cases assume that the
    // order is the same on all Java versions. Java 6 seems to use reverse lexical order, so we use that
    // here to make things deterministic.
    Collections.sort( serviceMethods, new Comparator<Method>() {
      public int compare( Method o1, Method o2 ) {
        return -o1.getName().compareTo( o2.getName() );
      }
    } );
    methods = processMethods( serviceMethods.toArray( new Method[serviceMethods.size()] ) );

    for ( String extraClassName : getExtraClasses() ) {
      Class<?> extraClass = Class.forName( extraClassName, true, classLoader );
      if ( typeTable.getSimpleSchemaTypeName( extraClassName ) == null ) {
        generateSchema( extraClass );
      }
    }

    return schemaMap.values();
  }

  @Override
  protected Method[] processMethods( Method[] declaredMethods ) throws Exception {
    ArrayList<Method> list = new ArrayList<>();
    //short the elements in the array
    Arrays.sort( declaredMethods, new MathodComparator() );

    // since we do not support overload
    Map<String, Method> uniqueMethods = new LinkedHashMap<>();
    XmlSchemaComplexType methodSchemaType;
    XmlSchemaSequence sequence = null;

    for ( Method jMethod : declaredMethods ) {
      if ( jMethod.isBridge() || jMethod.getDeclaringClass().getName().equals( Object.class.getName() ) ) {
        continue;
      }

      String methodName = jMethod.getName();
      // no need to think abt this method, since that is system
      // config method
      if ( excludeMethods.contains( methodName ) ) {
        continue;
      }

      if ( uniqueMethods.get( methodName ) != null ) {
        log.warn( "We don't support method overloading. Ignoring [" + methodName + "]" );
        continue;
      }
      if ( !Modifier.isPublic( jMethod.getModifiers() ) ) {
        // no need to generate Schema for non public methods
        continue;
      }
      boolean addToService = false;
      AxisOperation axisOperation = service.getOperation( new QName( methodName ) );
      if ( axisOperation == null ) {
        axisOperation = Utils.getAxisOperationForJmethod( jMethod );
        addToService = true;
      }
      // by now axis operation should be assigned but we better recheck & add the paramether
      if ( axisOperation != null ) {
        axisOperation.addParameter( "JAXRSAnnotaion", JAXRSUtils.getMethodModel( this.classModel, jMethod ) );
      }
      // Maintain a list of methods we actually work with
      list.add( jMethod );

      processException( jMethod, axisOperation );
      uniqueMethods.put( methodName, jMethod );
      Class<?>[] parameters = jMethod.getParameterTypes();
      String[] parameterNames = null;
      AxisMessage inMessage = axisOperation.getMessage( WSDLConstants.MESSAGE_LABEL_IN_VALUE );
      if ( inMessage != null ) {
        inMessage.setName( methodName + Java2WSDLConstants.MESSAGE_SUFFIX );
      }
      if ( parameters.length > 0 ) {
        parameterNames = methodTable.getParameterNames( methodName );
        // put the parameter names to use it for parsing
        service.addParameter( methodName, parameterNames );
      }

      // we need to add the method operation wrapper part even to
      // empty parameter operations
      sequence = new XmlSchemaSequence();

      String requestElementSuffix = getRequestElementSuffix();
      String requestLocalPart = methodName;
      if ( requestElementSuffix != null ) {
        requestLocalPart += requestElementSuffix;
      }

      methodSchemaType = createSchemaTypeForMethodPart( requestLocalPart );
      methodSchemaType.setParticle( sequence );
      inMessage.setElementQName( typeTable.getQNamefortheType( requestLocalPart ) );

      Parameter param = service.getParameter( Java2WSDLConstants.MESSAGE_PART_NAME_OPTION_LONG );
      if ( param != null ) {
        inMessage.setPartName( (String) param.getValue() );
      }

      service.addMessageElementQNameToOperationMapping( methodSchemaType.getQName(), axisOperation );

      Annotation[][] parameterAnnotation = jMethod.getParameterAnnotations();

      Type[] genericParameterTypes = jMethod.getGenericParameterTypes();
      for ( int j = 0; j < parameters.length; j++ ) {
        Class<?> methodParameter = parameters[j];
        String parameterName = getParameterName( parameterAnnotation, j, parameterNames );
        if ( nonRpcMethods.contains( jMethod.getName() ) ) {
          generateSchemaForType( sequence, null, jMethod.getName() );
          break;

        } else if ( methodParameter != null && Document.class.isAssignableFrom( methodParameter ) ) {
          generateSchemaTypeForDocument( sequence, parameterName );

        } else if ( methodParameter != null && Map.class.isAssignableFrom( methodParameter ) ) {
          generateWrappedSchemaTypeForMap( sequence, genericParameterTypes[j], parameterName );

        } else if ( methodParameter != null && Collection.class.isAssignableFrom( methodParameter ) ) {
          generateWrappedSchemaTypeForCollection( sequence, genericParameterTypes[j], parameterName );

        } else if ( methodParameter != null && Enum.class.isAssignableFrom( methodParameter ) ) {
          generateWrappedSchemaTypeForEnum( sequence, parameterName, methodParameter, false );
        } else {
          Type genericParameterType = genericParameterTypes[j];
          Type genericType = null;
          if ( genericParameterType instanceof ParameterizedType ) {
            ParameterizedType aType = (ParameterizedType) genericParameterType;
            Type[] parameterArgTypes = aType.getActualTypeArguments();
            genericType = parameterArgTypes[0];
            generateSchemaForType( sequence, genericType, parameterName, true );
          } else {
            if ( methodParameter.isArray() ) {
              Class<?> componentType = methodParameter.getComponentType();
              if ( Enum.class.isAssignableFrom( componentType ) ) {
                generateWrappedSchemaTypeForEnum( sequence, parameterName, componentType, true );
              } else {
                generateSchemaForType( sequence, methodParameter, parameterName );
              }

            } else {
              generateSchemaForType( sequence, methodParameter, parameterName );
            }
          }
        }
      }
      // for its return type
      Class<?> returnType = jMethod.getReturnType();
      if ( !"void".equals( jMethod.getReturnType().getName() ) ) {
        String partQname = methodName + RESPONSE;
        methodSchemaType = createSchemaTypeForMethodPart( partQname );
        sequence = new XmlSchemaSequence();
        methodSchemaType.setParticle( sequence );
        String returnName = "return";
        Type genericParameterType = jMethod.getGenericReturnType();
        if ( nonRpcMethods.contains( jMethod.getName() ) ) {
          generateSchemaForType( sequence, null, returnName );

        } else if ( returnType != null && Document.class.isAssignableFrom( returnType ) ) {
          generateSchemaTypeForDocument( sequence, returnName );

        } else if ( Map.class.isAssignableFrom( returnType ) ) {
          if ( genericParameterType instanceof ParameterizedType ) {
            generateWrappedSchemaTypeForMap( sequence, genericParameterType, returnName );
          } else {
            generateWrappedSchemaTypeForMap( sequence, returnType, returnName );
          }
        } else if ( Collection.class.isAssignableFrom( returnType ) ) {
          if ( genericParameterType instanceof ParameterizedType ) {
            generateWrappedSchemaTypeForCollection( sequence, genericParameterType, returnName );
          } else {
            generateWrappedSchemaTypeForCollection( sequence, genericParameterType, returnName );
          }
        } else if ( Enum.class.isAssignableFrom( returnType ) ) {
          generateWrappedSchemaTypeForEnum( sequence, returnName, returnType, false );
        } else if ( genericParameterType instanceof ParameterizedType ) {
          ParameterizedType aType = (ParameterizedType) genericParameterType;
          Type[] parameterArgTypes = aType.getActualTypeArguments();
          generateSchemaForType( sequence, parameterArgTypes[0], returnName, true );
        } else {
          if ( returnType.isArray() ) {
            Class<?> returnComponentType = returnType.getComponentType();
            if ( Enum.class.isAssignableFrom( returnComponentType ) ) {
              generateWrappedSchemaTypeForEnum( sequence, returnName, returnComponentType, true );
            } else {
              generateSchemaForType( sequence, returnType, returnName );
            }
          } else {
            generateSchemaForType( sequence, returnType, returnName );
          }
        }

        AxisMessage outMessage = axisOperation.getMessage( WSDLConstants.MESSAGE_LABEL_OUT_VALUE );
        outMessage.setElementQName( typeTable.getQNamefortheType( partQname ) );
        outMessage.setName( partQname );

        Parameter outparam = service.getParameter( Java2WSDLConstants.MESSAGE_PART_NAME_OPTION_LONG );
        if ( outparam != null ) {
          outMessage.setPartName( (String) outparam.getValue() );
        }

        service.addMessageElementQNameToOperationMapping( methodSchemaType.getQName(), axisOperation );
      }
      if ( addToService ) {
        service.addOperation( axisOperation );
      }
    }
    return list.toArray( new Method[list.size()] );
  }

  /**
   * This method will generate Schema element for all the excetion types in a given JMethod
   * - No matter what it will generate Schema element for java.lang.Exception so that for other
   * exception which extend java.lang.Excetion can use as the base class type
   */
  @Override
  protected void processException( Method jMethod,
                                  AxisOperation axisOperation ) throws Exception {
    XmlSchemaComplexType methodSchemaType;
    XmlSchemaSequence sequence;
    if ( jMethod.getExceptionTypes().length > 0 ) {
      for ( Class<?> extype : jMethod.getExceptionTypes() ) {
        if ( AxisFault.class.getName().equals( extype.getName() ) ) {
          continue;
        }
        String partQname = this.service.getName() + getSimpleClassName( extype );
        methodSchemaType = createSchemaTypeForFault( partQname );
        QName elementName =
          new QName( this.schemaTargetNameSpace, partQname, this.schema_namespace_prefix );
        sequence = new XmlSchemaSequence();
        if ( Exception.class.getName().equals( extype.getName() ) ) {
          if ( typeTable.getComplexSchemaType( Exception.class.getName() ) == null ) {
            generateComplexTypeforException();
          }
          QName schemaTypeName = typeTable.getComplexSchemaType( Exception.class.getName() );
          addContentToMethodSchemaType( sequence,
            schemaTypeName,
            partQname,
            false );
          methodSchemaType.setParticle( sequence );
          typeTable.addComplexSchema( Exception.class.getPackage().getName(),
            methodSchemaType.getQName() );
          resolveSchemaNamespace( Exception.class.getPackage().getName() );
          addImport( getXmlSchema( schemaTargetNameSpace ), schemaTypeName );
        } else {
          generateSchemaForType( sequence, extype, getSimpleClassName( extype ) );
          methodSchemaType.setParticle( sequence );
        }

        typeTable.addComplexSchema( partQname, elementName );

        if ( AxisFault.class.getName().equals( extype.getName() ) ) {
          continue;
        }
        AxisMessage faultMessage = new AxisMessage();
        faultMessage.setName( this.service.getName() + getSimpleClassName( extype ) );
        faultMessage.setElementQName( typeTable.getQNamefortheType( partQname ) );

        Parameter param = service.getParameter( Java2WSDLConstants.MESSAGE_PART_NAME_OPTION_LONG );
        if ( param != null ) {
          faultMessage.setPartName( (String) param.getValue() );
        }

        axisOperation.setFaultMessages( faultMessage );
      }
    }
  }

  private QName generateSchemaForType( XmlSchemaSequence sequence, Class<?> type, String partName )throws Exception {
    boolean isArrayType = false;
    try {
      if ( type != null ) {
        isArrayType = type.isArray();
      }
      if ( isArrayType ) {
        if ( type.getComponentType().isArray() ) {
          // this is a double array element
          Class<?> simpleType = type.getComponentType();
          String simpleTypeName = "";
          while ( simpleType.isArray() ) {
            simpleTypeName += "ArrayOf";
            simpleType = simpleType.getComponentType();
          }
          simpleTypeName += getSimpleClassName( simpleType );
          return processParameterArrayTypes( sequence, type, partName, simpleTypeName );
        } else {
          type = type.getComponentType();
        }
      }
      if ( AxisFault.class.getName().equals( type ) ) {
        return null;
      }
      String classTypeName;
      if ( type == null ) {
        classTypeName = "java.lang.Object";
      } else {
        classTypeName = type.getName();
      }
      if ( isArrayType && "byte".equals( classTypeName ) ) {
        classTypeName = "base64Binary";
        isArrayType = false;
      }

      if ( isDataHandler( type ) ) {
        classTypeName = "base64Binary";
      }

      return generateSchemaTypeforNameCommon( sequence, partName, isArrayType, type, classTypeName );
    } catch ( ClassCastException cex ) {
      log.debug( "Generic Type is not a Class type", cex );
      return null;
    }
  }

  private QName generateSchemaForType( XmlSchemaSequence sequence, Type genericType, String partName, boolean isArrayType )
    throws Exception {

    try {
      Class<?> type;
      if ( genericType instanceof GenericArrayType ) {
        // this is a double array element
        Class<?> simpleType = (Class<?>) ( (GenericArrayType) genericType ).getGenericComponentType();
        String simpleTypeName = "";
        while ( simpleType.isArray() ) {
          simpleTypeName += "ArrayOf";
          simpleType = simpleType.getComponentType();
        }
        simpleTypeName += simpleType.getSimpleName();

        XmlSchema xmlSchema = getXmlSchema( schemaTargetNameSpace );
        if ( xmlSchema.getTypeByName( simpleTypeName ) == null ) {
          XmlSchemaComplexType xmlSchemaComplexType = new XmlSchemaComplexType( xmlSchema, false );
          XmlSchemaSequence xmlSchemaSequence = new XmlSchemaSequence();
          xmlSchemaComplexType.setParticle( xmlSchemaSequence );
          generateSchemaForType( xmlSchemaSequence, simpleType, "array", true );
          xmlSchemaComplexType.setName( simpleTypeName );
          xmlSchema.getItems().add( xmlSchemaComplexType );
          xmlSchema.getSchemaTypes().put( new QName( xmlSchema.getTargetNamespace(), simpleTypeName ),
            xmlSchemaComplexType );
        }

        if ( isGenerateWrappedArrayTypes ) {
          XmlSchemaElement xmlSchemaElement = new XmlSchemaElement( xmlSchema, false );
          xmlSchemaElement.setName( partName + "Wrapper" );
          xmlSchemaElement.setNillable( true );
          sequence.getItems().add( xmlSchemaElement );

          String complexTypeName = simpleTypeName + "Wrapper";

          XmlSchemaComplexType xmlSchemaComplexType = new XmlSchemaComplexType( xmlSchema, false );
          XmlSchemaSequence xmlSchemaSequence = new XmlSchemaSequence();
          xmlSchemaComplexType.setParticle( xmlSchemaSequence );
          xmlSchemaComplexType.setName( complexTypeName );

          xmlSchema.getItems().add( xmlSchemaComplexType );
          xmlSchema.getSchemaTypes().put( new QName( schemaTargetNameSpace, xmlSchemaComplexType.getName() ),
            xmlSchemaComplexType );
          addContentToMethodSchemaType( xmlSchemaSequence, new QName( xmlSchema.getTargetNamespace(), simpleTypeName ),
            "array", true );

          xmlSchemaElement.setSchemaType( xmlSchemaComplexType );
          xmlSchemaElement.setSchemaTypeName( new QName( schemaTargetNameSpace, xmlSchemaComplexType.getName() ) );
          return new QName( xmlSchema.getTargetNamespace(), complexTypeName );
        } else {
          addContentToMethodSchemaType( sequence, new QName( xmlSchema.getTargetNamespace(), simpleTypeName ),
            partName, true );
          return new QName( xmlSchema.getTargetNamespace(), simpleTypeName );
        }


      } else {
        type = (Class<?>) genericType;
      }
      if ( AxisFault.class.getName().equals( type ) ) {
        return null;
      }
      String classTypeName;
      if ( type == null ) {
        classTypeName = "java.lang.Object";
      } else {
        classTypeName = type.getName();
      }
      if ( isArrayType && "byte".equals( classTypeName ) ) {
        classTypeName = "base64Binary";
        isArrayType = false;
      }

      if ( isDataHandler( type ) ) {
        classTypeName = "base64Binary";
      }

      return generateSchemaTypeforNameCommon( sequence, partName, isArrayType, type, classTypeName );
    } catch ( ClassCastException cex ) {
      log.debug( "Generic Type is not a Class type", cex );
      return null;
    }
  }

  /**
   * Generate schema construct for given type
   *
   * @param javaClassType : Class to whcih need to generate Schema
   * @return : Generated QName
   */
  @Override
  protected QName generateSchema( Class<?> javaClassType ) throws Exception {
    Class javaType = javaClassType;
    String name = null;
    QName schemaTypeName = null;
    boolean isSchemaLoopedFound = false;
    if ( javaClassType.isInterface() ) {
      List<? extends Object> pentahoObjs = PentahoSystem.getAll( javaClassType, null );

      if ( !pentahoObjs.isEmpty() ) {
        for ( Object pentahoObj : pentahoObjs ) {
          if ( pentahoObj != null ) {
            javaType = pentahoObj.getClass();
            name = getClassName( javaType );
            schemaTypeName = typeTable.getComplexSchemaType( name );
            if ( schemaTypeName == null ) {
              isSchemaLoopedFound = true;
              break;
            }
          }
        }
      } else {
        // The get vs getAll methods return different values, we need to verify both ways
        Object pentahoObj = PentahoSystem.get( javaClassType, null );
        if ( pentahoObj != null ) {
          javaType = pentahoObj.getClass();
        }
      }
    }

    if ( !isSchemaLoopedFound ) {
      name = getClassName( javaType );
      schemaTypeName = typeTable.getComplexSchemaType( name );
    }

    if ( schemaTypeName == null ) {
      String simpleName = getSimpleClassName( javaType );

      String packageName = getQualifiedName( javaType.getPackage() );
      String targetNameSpace = resolveSchemaNamespace( packageName );

      XmlSchema xmlSchema = getXmlSchema( targetNameSpace );
      String targetNamespacePrefix = targetNamespacePrefixMap.get( targetNameSpace );
      if ( targetNamespacePrefix == null ) {
        targetNamespacePrefix = generatePrefix();
        targetNamespacePrefixMap.put( targetNameSpace, targetNamespacePrefix );
      }

      XmlSchemaComplexType complexType = new XmlSchemaComplexType( xmlSchema, false );
      XmlSchemaSequence sequence = new XmlSchemaSequence();
      XmlSchemaComplexContentExtension complexExtension = new XmlSchemaComplexContentExtension();

      schemaTypeName = new QName( targetNameSpace, simpleName, targetNamespacePrefix );

      Class<?> sup = javaType.getSuperclass();
      if ( ( sup != null )
        && ( !"java.lang.Object".equals( sup.getName() ) )
        && ( !"java.lang.Exception".equals( sup.getName() ) )
        && !getQualifiedName( sup.getPackage() ).startsWith( "org.apache.axis2" )
        && !getQualifiedName( sup.getPackage() ).startsWith( "java.util" ) ) {
        String superClassName = sup.getName();
        String superclassname = getSimpleClassName( sup );
        String tgtNamespace;
        String tgtNamespacepfx;
        QName qName = typeTable.getSimpleSchemaTypeName( superClassName );
        if ( qName != null ) {
          tgtNamespace = qName.getNamespaceURI();
          tgtNamespacepfx = qName.getPrefix();
        } else {
          tgtNamespace = resolveSchemaNamespace( getQualifiedName( sup.getPackage() ) );
          tgtNamespacepfx = targetNamespacePrefixMap.get( tgtNamespace );
          QName superClassQname = generateSchema( sup );
          if ( superClassQname != null ) {
            tgtNamespacepfx = superClassQname.getPrefix();
            tgtNamespace = superClassQname.getNamespaceURI();
          }
        }
        if ( tgtNamespacepfx == null ) {
          tgtNamespacepfx = generatePrefix();
          targetNamespacePrefixMap.put( tgtNamespace, tgtNamespacepfx );
        }
        //if the parent class package name is differ from the child
        if ( !( (NamespaceMap) xmlSchema.getNamespaceContext() ).values().
          contains( tgtNamespace ) ) {
          XmlSchemaImport importElement = new XmlSchemaImport( xmlSchema );
          importElement.setNamespace( tgtNamespace );
          xmlSchema.getItems().add( importElement );
          ( (NamespaceMap) xmlSchema.getNamespaceContext() ).
            put( generatePrefix(), tgtNamespace );
        }

        QName basetype = new QName( tgtNamespace, superclassname, tgtNamespacepfx );
        complexExtension.setBaseTypeName( basetype );
        complexExtension.setParticle( sequence );
        XmlSchemaComplexContent contentModel = new XmlSchemaComplexContent();
        contentModel.setContent( complexExtension );
        complexType.setContentModel( contentModel );

      } else {
        complexType.setParticle( sequence );
      }

      complexType.setName( simpleName );

      if ( Modifier.isAbstract( javaType.getModifiers() ) ) {
        complexType.setAbstract( true );
      }

      xmlSchema.getItems().add( complexType );
      xmlSchema.getSchemaTypes().put( schemaTypeName, complexType );

      // adding this type to the table
      typeTable.addComplexSchema( name, schemaTypeName );
      // adding this type's package to the table, to support inheritance.
      typeTable.addComplexSchema( getQualifiedName( javaType.getPackage() ), schemaTypeName );

      typeTable.addClassNameForQName( schemaTypeName, name );

      BeanExcludeInfo beanExcludeInfo = null;
      if ( service.getExcludeInfo() != null ) {
        beanExcludeInfo = service.getExcludeInfo().getBeanExcludeInfoForClass( getClassName( javaType ) );
      }

      // we need to get properties only for this bean. hence ignore the super
      // class properties
      BeanInfo beanInfo = Introspector.getBeanInfo( javaType, javaType.getSuperclass() );

      for ( PropertyDescriptor property : beanInfo.getPropertyDescriptors() ) {
        String propertyName = property.getName();
        if ( !property.getName().equals( "class" ) && ( property.getPropertyType() != null ) ) {
          if ( property.getReadMethod() != null
            && ( ( beanExcludeInfo == null )
            || !beanExcludeInfo.isExcludedProperty( propertyName ) ) ) {
            Type genericFieldType = property.getReadMethod().getGenericReturnType();
            if ( genericFieldType instanceof ParameterizedType ) {
              ParameterizedType aType = (ParameterizedType) genericFieldType;
              Type[] fieldArgTypes = aType.getActualTypeArguments();
              if ( Map.class.isAssignableFrom( (Class) aType.getRawType() ) ) {
                generateWrappedSchemaTypeForMap( sequence, aType, propertyName );

              } else if ( Collection.class.isAssignableFrom( (Class) aType.getRawType() ) ) {
                generateWrappedSchemaTypeForCollection( sequence, aType, propertyName );
              } else {
                try {
                  generateSchemaforGenericFields( xmlSchema, sequence, fieldArgTypes[0], propertyName );
                } catch ( Exception e ) {
                  generateSchemaforFieldsandProperties( xmlSchema, sequence, property.getPropertyType(), propertyName,
                    property.getPropertyType().isArray() );
                }
              }

            } else if ( genericFieldType != null && !( genericFieldType instanceof ParameterizedType )
              && Document.class.isAssignableFrom( (Class) genericFieldType ) ) {
              generateSchemaTypeForDocument( sequence, propertyName );

            } else {
              if ( genericFieldType != null && Map.class.isAssignableFrom( (Class) genericFieldType ) ) {
                generateWrappedSchemaTypeForMap( sequence, genericFieldType, propertyName );

              } else if ( genericFieldType != null && Collection.class.isAssignableFrom( (Class) genericFieldType ) ) {
                generateWrappedSchemaTypeForCollection( sequence, genericFieldType, propertyName );

              } else if ( genericFieldType != null && Enum.class.isAssignableFrom( (Class) genericFieldType ) ) {
                generateWrappedSchemaTypeForEnum( sequence, propertyName, (Class) genericFieldType, false );

              } else {
                generateSchemaforFieldsandProperties( xmlSchema, sequence, property.getPropertyType(), propertyName,
                  property.getPropertyType().isArray() );
              }
            }
          }
        }
      }
    }
    return schemaTypeName;
  }

  private QName generateSchemaTypeforNameCommon( XmlSchemaSequence sequence, String partName, boolean isArrayType,
                                                 Class<?> type, String classTypeName ) throws Exception {
    QName schemaTypeName = typeTable.getSimpleSchemaTypeName( classTypeName );
    if ( schemaTypeName == null ) {
      schemaTypeName = generateSchema( type );
      if ( isGenerateWrappedArrayTypes && isArrayType ) {
        XmlSchema xmlSchema = getXmlSchema( schemaTargetNameSpace );
        XmlSchemaElement xmlSchemaElement = new XmlSchemaElement( xmlSchema, false );
        xmlSchemaElement.setName( partName + "Wrapper" );
        xmlSchemaElement.setNillable( true );
        sequence.getItems().add( xmlSchemaElement );

        String complexTypeName = schemaTypeName.getLocalPart() + "Wrapper";

        XmlSchemaComplexType xmlSchemaComplexType = null;
        if ( xmlSchema.getTypeByName( complexTypeName ) == null ) {
          xmlSchemaComplexType = new XmlSchemaComplexType( xmlSchema, false );
          XmlSchemaSequence xmlSchemaSequence = new XmlSchemaSequence();
          xmlSchemaComplexType.setParticle( xmlSchemaSequence );
          xmlSchemaComplexType.setName( complexTypeName );

          xmlSchema.getItems().add( xmlSchemaComplexType );
          xmlSchema.getSchemaTypes().put( new QName( schemaTargetNameSpace, xmlSchemaComplexType.getName() ),
            xmlSchemaComplexType );
          addContentToMethodSchemaType( xmlSchemaSequence, schemaTypeName, "array", isArrayType );
        } else {
          xmlSchemaComplexType = (XmlSchemaComplexType) xmlSchema.getTypeByName( complexTypeName );
        }

        xmlSchemaElement.setSchemaType( xmlSchemaComplexType );
        xmlSchemaElement.setSchemaTypeName( new QName( schemaTargetNameSpace, xmlSchemaComplexType.getName() ) );

      } else {
        addContentToMethodSchemaType( sequence, schemaTypeName, partName, isArrayType );
      }

      String schemaNamespace;
      schemaNamespace = resolveSchemaNamespace( getQualifiedName( type.getPackage() ) );
      addImport( getXmlSchema( schemaNamespace ), schemaTypeName );

    } else {
      if ( isGenerateWrappedArrayTypes && isArrayType ) {
        XmlSchema xmlSchema = getXmlSchema( schemaTargetNameSpace );
        XmlSchemaElement xmlSchemaElement = new XmlSchemaElement( xmlSchema, false );
        xmlSchemaElement.setName( partName + "Wrapper" );
        xmlSchemaElement.setNillable( true );
        sequence.getItems().add( xmlSchemaElement );

        String complexTypeName = schemaTypeName.getLocalPart() + "Wrapper";

        XmlSchemaComplexType xmlSchemaComplexType = null;
        if ( xmlSchema.getTypeByName( complexTypeName ) == null ) {
          xmlSchemaComplexType = new XmlSchemaComplexType( xmlSchema, false );
          XmlSchemaSequence xmlSchemaSequence = new XmlSchemaSequence();
          xmlSchemaComplexType.setParticle( xmlSchemaSequence );
          xmlSchemaComplexType.setName( complexTypeName );

          xmlSchema.getItems().add( xmlSchemaComplexType );
          xmlSchema.getSchemaTypes().put( new QName( schemaTargetNameSpace, xmlSchemaComplexType.getName() ),
            xmlSchemaComplexType );
          addContentToMethodSchemaType( xmlSchemaSequence, schemaTypeName, "array", isArrayType );
        } else {
          xmlSchemaComplexType = (XmlSchemaComplexType) xmlSchema.getTypeByName( complexTypeName );
        }

        xmlSchemaElement.setSchemaType( xmlSchemaComplexType );
        xmlSchemaElement.setSchemaTypeName( new QName( schemaTargetNameSpace, xmlSchemaComplexType.getName() ) );

      } else {
        addContentToMethodSchemaType( sequence, schemaTypeName, partName, isArrayType );
      }
    }
    addImport( getXmlSchema( schemaTargetNameSpace ), schemaTypeName );
    return schemaTypeName;
  }

  private QName processParameterArrayTypes( XmlSchemaSequence sequence, Class<?> type, String partName,
                                            String simpleTypeName ) throws Exception {
    XmlSchema xmlSchema = getXmlSchema( schemaTargetNameSpace );
    if ( xmlSchema.getTypeByName( simpleTypeName ) == null ) {
      XmlSchemaComplexType xmlSchemaComplexType = new XmlSchemaComplexType( xmlSchema, false );
      XmlSchemaSequence xmlSchemaSequence = new XmlSchemaSequence();
      xmlSchemaComplexType.setParticle( xmlSchemaSequence );
      generateSchemaForType( xmlSchemaSequence, type.getComponentType(), "array" );
      xmlSchemaComplexType.setName( simpleTypeName );
      xmlSchema.getItems().add( xmlSchemaComplexType );
      xmlSchema.getSchemaTypes().put( new QName( xmlSchema.getTargetNamespace(), simpleTypeName ),
        xmlSchemaComplexType );
    }

    if ( isGenerateWrappedArrayTypes ) {
      XmlSchemaElement xmlSchemaElement = new XmlSchemaElement( xmlSchema, false );
      xmlSchemaElement.setName( partName + "Wrapper" );
      xmlSchemaElement.setNillable( true );
      sequence.getItems().add( xmlSchemaElement );

      String complexTypeName = simpleTypeName + "Wrapper";

      XmlSchemaComplexType xmlSchemaComplexType = new XmlSchemaComplexType( xmlSchema, false );
      XmlSchemaSequence xmlSchemaSequence = new XmlSchemaSequence();
      xmlSchemaComplexType.setParticle( xmlSchemaSequence );
      xmlSchemaComplexType.setName( complexTypeName );

      xmlSchema.getItems().add( xmlSchemaComplexType );
      xmlSchema.getSchemaTypes().put( new QName( schemaTargetNameSpace, xmlSchemaComplexType.getName() ),
        xmlSchemaComplexType );
      addContentToMethodSchemaType( xmlSchemaSequence, new QName( xmlSchema.getTargetNamespace(), simpleTypeName ),
        "array", true );

      xmlSchemaElement.setSchemaType( xmlSchemaComplexType );
      xmlSchemaElement.setSchemaTypeName( new QName( schemaTargetNameSpace, xmlSchemaComplexType.getName() ) );
      return new QName( xmlSchema.getTargetNamespace(), complexTypeName );
    } else {
      addContentToMethodSchemaType( sequence, new QName( xmlSchema.getTargetNamespace(), simpleTypeName ),
        partName, true );
      return new QName( xmlSchema.getTargetNamespace(), simpleTypeName );
    }
  }

  /**
   * Generate schema type for map parameter.
   *
   * @param entrySequence the entry sequence
   * @param parameterType the parameter type
   * @param elementName the element name
   * @throws Exception the exception
   */
  @Override
  protected void generateSchemaTypeForMapParameter( XmlSchemaSequence entrySequence, Type parameterType,
                                                    String elementName ) throws Exception {
    if ( parameterType instanceof ParameterizedType ) {
      if ( Map.class.isAssignableFrom( (Class) ( (ParameterizedType) parameterType ).getRawType() ) ) {
        generateWrappedSchemaTypeForMap( entrySequence, parameterType, elementName );

      } else if ( Collection.class.isAssignableFrom( (Class) ( (ParameterizedType) parameterType ).getRawType() ) ) {
        generateSchemaForCollection( entrySequence, parameterType, elementName );
      }
    } else {
      if ( Document.class.isAssignableFrom( (Class) parameterType ) ) {
        generateSchemaTypeForDocument( entrySequence, elementName );
      } else {
        generateSchemaForType( entrySequence, parameterType, elementName, false );
      }
    }
  }

  /**
   * Generate schema for collection.
   *
   * @param sequence the sequence
   * @param genericType the generic type
   * @param partName the part name
   * @return the q name
   * @throws Exception the exception
   */
  @Override
  protected QName generateSchemaForCollection( XmlSchemaSequence sequence, Type genericType,
                                               String partName ) throws Exception {

    if ( genericType instanceof ParameterizedType ) {
      ParameterizedType parameterizedType = (ParameterizedType) genericType;
      Type[] parameterizedTypes = parameterizedType.getActualTypeArguments();
      Type parameterType = parameterizedTypes[0];
      Type innerParameterType = Object.class;

      if ( parameterType instanceof GenericArrayType ) {
        log.debug( "not supported" );
        return null;
      }

      if ( parameterType instanceof ParameterizedType ) {
        ParameterizedType innerParameterizedType = (ParameterizedType) parameterType;
        Type[] innerParameterizedTypes = parameterizedType.getActualTypeArguments();
        innerParameterType = innerParameterizedTypes[0];
        XmlSchema xmlSchema = getXmlSchema( schemaTargetNameSpace );
        String simpleTypeName = getCollectionElementName( parameterType );

        if ( xmlSchema.getTypeByName( simpleTypeName ) == null ) {
          if ( Collection.class.isAssignableFrom( (Class<?>) innerParameterizedType.getRawType() ) ) {
            XmlSchemaComplexType xmlSchemaComplexType = new XmlSchemaComplexType( xmlSchema, false );
            XmlSchemaSequence xmlSchemaSequence = new XmlSchemaSequence();
            xmlSchemaComplexType.setParticle( xmlSchemaSequence );
            generateWrappedSchemaTypeForCollection( xmlSchemaSequence, innerParameterizedType, "array" );
            xmlSchemaComplexType.setName( simpleTypeName );
            xmlSchema.getItems().add( xmlSchemaComplexType );
            xmlSchema.getSchemaTypes().put( new QName( xmlSchema.getTargetNamespace(), simpleTypeName ),
              xmlSchemaComplexType );

            addContentToMethodSchemaType( sequence, new QName( xmlSchema.getTargetNamespace(), simpleTypeName ),
              partName, true );
            return new QName( xmlSchema.getTargetNamespace(), simpleTypeName );

          } else if ( Map.class.isAssignableFrom( (Class<?>) innerParameterizedType.getRawType() ) ) {
            return generateSchemaTypeForMap( sequence, innerParameterType, partName, true );

          }
        } else {
          addContentToMethodSchemaType( sequence, new QName( xmlSchema.getTargetNamespace(), simpleTypeName ),
            partName, true );
        }

      } else {
        return generateSchemaForType( sequence, parameterType, partName, true );
      }
    } else {
      return generateSchemaForType( sequence, genericType, partName, true );
    }
    return null;
  }

  private String getRequestElementSuffix() {
    String requestElementSuffix = null;
    Parameter param = service.getParameter( Java2WSDLConstants.REQUEST_ELEMENT_SUFFIX_OPTION_LONG );
    if ( param != null ) {
      requestElementSuffix = (String) param.getValue();
    }
    return requestElementSuffix;
  }

  private XmlSchemaComplexType createSchemaTypeForMethodPart( String localPartName ) {
    XmlSchema xmlSchema = getXmlSchema( schemaTargetNameSpace );
    QName elementName = new QName( this.schemaTargetNameSpace, localPartName, this.schema_namespace_prefix );

    XmlSchemaComplexType complexType = getComplexTypeForElement( xmlSchema, elementName );
    if ( complexType == null ) {
      complexType = new XmlSchemaComplexType( xmlSchema, false );

      XmlSchemaElement globalElement = new XmlSchemaElement( xmlSchema, false );
      globalElement.setName( localPartName );

      boolean disallowAnonTypes = isAnonymousTypesDisallowed();
      if ( disallowAnonTypes ) {
        String complexTypeName = localPartName.substring( 0, 1 ).toUpperCase() + localPartName.substring( 1 );
        complexType.setName( complexTypeName );
        globalElement.setSchemaTypeName( complexType.getQName() );
        xmlSchema.getItems().add( complexType );
        xmlSchema.getSchemaTypes().put( complexType.getQName(), complexType );
      } else {
        globalElement.setSchemaType( complexType );
      }

      xmlSchema.getItems().add( globalElement );
      xmlSchema.getElements().put( elementName, globalElement );
    }
    typeTable.addComplexSchema( localPartName, elementName );

    return complexType;
  }

  /**
   * Generate wrapped schema type for map.
   *
   * @param sequence the sequence
   * @param genericParameterType the generic parameter type
   * @param parameterName the parameter name
   * @throws Exception the exception
   */
  private void generateWrappedSchemaTypeForMap( XmlSchemaSequence sequence, Type genericParameterType,
                                                String parameterName ) throws Exception {
    generateSchemaTypeForMap( sequence, genericParameterType, parameterName, false );
  }

  /**
   * Generate wrapped schema type for collection.
   *
   * @param sequence the sequence
   * @param genericType the generic type
   * @param partName the part name
   * @throws Exception the exception
   */
  private void generateWrappedSchemaTypeForCollection( XmlSchemaSequence sequence, Type genericType,
                                                       String partName ) throws Exception {
    generateSchemaForCollection( sequence, genericType, partName );
  }

  /**
   * Generate wrapped schema type for Enum.
   *
   * @param sequence the sequence
   * @param methodParameterType the generic parameter type
   * @param parameterName the parameter name
   * @throws Exception the exception
   */
  private void generateWrappedSchemaTypeForEnum( XmlSchemaSequence sequence, String parameterName,
                                                 Class<?> methodParameterType, boolean isArray ) throws Exception {
    generateSchemaTypeForEnum( sequence, parameterName, isArray, methodParameterType );
  }

  private XmlSchemaComplexType createSchemaTypeForFault( String localPartName ) {
    XmlSchema xmlSchema = getXmlSchema( schemaTargetNameSpace );
    QName elementName = new QName( this.schemaTargetNameSpace, localPartName, this.schema_namespace_prefix );

    XmlSchemaComplexType complexType = getComplexTypeForElement( xmlSchema, elementName );
    if ( complexType == null ) {
      complexType = new XmlSchemaComplexType( xmlSchema, false );

      XmlSchemaElement globalElement = new XmlSchemaElement( xmlSchema, false );
      globalElement.setName( localPartName );

      boolean disallowAnonTypes = isAnonymousTypesDisallowed();
      if ( disallowAnonTypes ) {
        complexType.setName( localPartName );
        globalElement.setSchemaTypeName( complexType.getQName() );
        xmlSchema.getItems().add( complexType );
        xmlSchema.getSchemaTypes().put( complexType.getQName(), complexType );
      } else {
        globalElement.setSchemaType( complexType );
      }

      xmlSchema.getItems().add( globalElement );
      xmlSchema.getElements().put( elementName, globalElement );

    }
    return complexType;
  }

  private void generateComplexTypeforException() {
    XmlSchemaSequence sequence = new XmlSchemaSequence();
    XmlSchema xmlSchema = getXmlSchema( schemaTargetNameSpace );
    QName elementName = new QName( schemaTargetNameSpace, "Exception", schema_namespace_prefix );
    XmlSchemaComplexType complexType = new XmlSchemaComplexType( xmlSchema, false );
    complexType.setName( "Exception" );
    xmlSchema.getItems().add( complexType );
    xmlSchema.getSchemaTypes().put( elementName, complexType );
    typeTable.addComplexSchema( Exception.class.getName(), elementName );
    QName schemaTypeName = new QName( Java2WSDLConstants.URI_2001_SCHEMA_XSD, "string" );
    addContentToMethodSchemaType( sequence, schemaTypeName, "Message", false );
    complexType.setParticle( sequence );
  }

  /**
   * Gets the collection element name.
   *
   * @param genericType the generic type
   * @return the collection element name
   */
  private String getCollectionElementName( Type genericType ) {
    if ( genericType instanceof ParameterizedType ) {
      ParameterizedType parameterizedType = (ParameterizedType) genericType;
      if ( Map.class.isAssignableFrom( (Class<?>) parameterizedType.getRawType() ) ) {
        Type[] parameterizedArgs = parameterizedType.getActualTypeArguments();
        String first = getCollectionElementName( parameterizedArgs[0] );
        String second = getCollectionElementName( parameterizedArgs[1] );
        return "MapOf" + first + "And" + second;

      } else if ( Collection.class
        .isAssignableFrom( (Class<?>) parameterizedType.getRawType() ) ) {
        Type[] parameterizedArgs = parameterizedType.getActualTypeArguments();
        return "ArrayOf" + getCollectionElementName( parameterizedArgs[0] );
      } else {
        return getCollectionElementName( parameterizedType );
      }

    } else {
      return ( (Class) genericType ).getSimpleName();

    }

  }

  private boolean isAnonymousTypesDisallowed() {
    boolean disallowAnonTypes = false;
    Parameter param = service.getParameter( Java2WSDLConstants.DISALLOW_ANON_TYPES_OPTION_LONG );
    if ( param != null ) {
      disallowAnonTypes = JavaUtils.isTrueExplicitly( param.getValue() );
    }
    return disallowAnonTypes;
  }

  /**
   * Generate schema type for Enum.
   *
   * @param sequence the sequence
   * @param classType the generic parameter type
   * @param parameterName the parameter name
   * @param isArrayType parameter is an array or not
   * @return the q name
   * @throws Exception the exception
   */
  private QName generateSchemaTypeForEnum( XmlSchemaSequence sequence, String parameterName, boolean isArrayType,
                                           Class<?> classType ) {
    if ( Enum.class .isAssignableFrom( classType ) ) {
      XmlSchema xmlSchema = getXmlSchema( Constants.AXIS2_ENUM_NAMESPACE_URI );
      String targetNamespacePrefix = targetNamespacePrefixMap
        .get( Constants.AXIS2_ENUM_NAMESPACE_URI );
      String enumClass = classType.getName().substring( classType.getName().lastIndexOf( "$" ) + 1,
        classType.getName().length() );
      QName enumQname = new QName( Constants.AXIS2_ENUM_NAMESPACE_URI, enumClass, targetNamespacePrefix );
      // check weather this enum class have already added to schema
      if ( typeTable.getSimpleTypeEnum( classType.getName() ) == null ) {
        XmlSchemaSimpleType simpleType = new XmlSchemaSimpleType( xmlSchema, false );
        simpleType.setName( enumClass );
        XmlSchemaSimpleTypeRestriction restriction = new XmlSchemaSimpleTypeRestriction();
        restriction.setBaseTypeName( Constants.XSD_STRING );
        List enumList = Arrays.asList( classType.getEnumConstants() );
        for ( Object enumObj : enumList ) {        // add all enum constants to restriction facet
          restriction.getFacets().add( new XmlSchemaEnumerationFacet( enumObj.toString(), false ) );
        }
        simpleType.setContent( restriction );
        xmlSchema.getItems().add( simpleType );       // add enum to wsdl
        typeTable.addSimpleTypeEnum( classType.getName(), enumQname ); //add to typetable

        // It's also seen that sometimes Enums are searched for in the ComplexTypeName, some are added there automatically
        // So let's ensure that others are as well.
        if ( typeTable.getComplexSchemaType( classType.getName() ) == null ) {
          typeTable.addComplexSchema( classType.getName(), enumQname );
        }
      }

      XmlSchemaElement entryElement = new XmlSchemaElement( xmlSchema, false );
      entryElement.setName( Constants.ENUM_ELEMENT_NAME );
      entryElement.setNillable( true );
      entryElement.setSchemaTypeName( enumQname );
      QName schemaTypeName = new QName( Constants.AXIS2_ENUM_NAMESPACE_URI, enumClass );
      addImport( getXmlSchema( schemaTargetNameSpace ), schemaTypeName );
      if ( sequence != null ) {
        XmlSchemaComplexType parameterType = new XmlSchemaComplexType( xmlSchema, false );
        QName parameterTypeName = new QName( Constants.AXIS2_ENUM_NAMESPACE_URI, enumClass, targetNamespacePrefix );
        XmlSchemaSequence parameterSequence = new XmlSchemaSequence();
        parameterSequence.getItems().add( entryElement );
        parameterType.setParticle( parameterSequence );

        XmlSchemaElement parameterElement = new XmlSchemaElement( xmlSchema, false );
        parameterElement.setName( parameterName );
        if ( isArrayType ) {
          parameterElement.setMaxOccurs( Long.MAX_VALUE );
        }
        parameterElement.setMinOccurs( 0 );
        parameterElement.setNillable( true );
        sequence.getItems().add( parameterElement );
        parameterElement.setSchemaTypeName( parameterTypeName );
        return parameterTypeName;
      }
      return enumQname;
    } else {
      // if classType is not enum
      return null;
    }
  }
}
