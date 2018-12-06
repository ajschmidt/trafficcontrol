/*
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.comcast.cdn.traffic_control.traffic_router.secure;

import com.comcast.cdn.traffic_control.traffic_router.protocol.RouterNioEndpoint;
import com.comcast.cdn.traffic_control.traffic_router.shared.CertificateData;
import org.apache.log4j.Logger;
import sun.security.tools.keytool.CertAndKeyGen;
import sun.security.util.ObjectIdentifier;
import sun.security.x509.BasicConstraintsExtension;
import sun.security.x509.CertificateExtensions;
import sun.security.x509.ExtendedKeyUsageExtension;
import sun.security.x509.KeyUsageExtension;

import java.security.PrivateKey;
import java.util.ArrayList;
import java.util.List;
import java.util.HashMap;
import java.util.Map;
import java.util.Vector;

import sun.security.x509.X500Name;
import java.security.cert.X509Certificate;
import java.util.Date;

public class CertificateRegistry {
	public static final String DEFAULT_SSL_KEY = "default.invalid";
	private static final Logger log = Logger.getLogger(CertificateRegistry.class);
	private CertificateDataConverter certificateDataConverter = new CertificateDataConverter();
	volatile private Map<String, HandshakeData>	handshakeDataMap = new HashMap<>();
	private RouterNioEndpoint sslEndpoint = null;
	final private Map<String, CertificateData> previousData = new HashMap<>();

	// Recommended Singleton Pattern implementation
	// https://community.oracle.com/docs/DOC-918906
	private CertificateRegistry() {
	}

	public static CertificateRegistry getInstance() {
		return CertificateRegistryHolder.DELIVERY_SERVICE_CERTIFICATES;
	}

	@SuppressWarnings("PMD.UseArrayListInsteadOfVector")
	public static HandshakeData createDefaultSsl() {
		try {
			final CertificateExtensions extensions = new CertificateExtensions();
			final KeyUsageExtension keyUsageExtension = new KeyUsageExtension();
			keyUsageExtension.set(KeyUsageExtension.DIGITAL_SIGNATURE, true);
			keyUsageExtension.set(KeyUsageExtension.KEY_ENCIPHERMENT, true);
			keyUsageExtension.set(KeyUsageExtension.KEY_CERTSIGN, true);
			extensions.set(keyUsageExtension.getExtensionId().toString(), keyUsageExtension);
			final Vector<ObjectIdentifier> objectIdentifiers = new Vector<>();
			objectIdentifiers.add(new ObjectIdentifier("1.3.6.1.5.5.7.3.1"));
			objectIdentifiers.add(new ObjectIdentifier("1.3.6.1.5.5.7.3.2"));
			final ExtendedKeyUsageExtension extendedKeyUsageExtension = new ExtendedKeyUsageExtension( true,
					objectIdentifiers);
			extensions.set(extendedKeyUsageExtension.getExtensionId().toString(), extendedKeyUsageExtension);
			extensions.set(BasicConstraintsExtension.NAME, new BasicConstraintsExtension(true,
					new BasicConstraintsExtension(true,-1).getExtensionValue()));
			final CertAndKeyGen certGen = new CertAndKeyGen("RSA", "SHA1WithRSA", null);
			certGen.generate(2048);

			//Generate self signed certificate
			final X509Certificate[] chain = new X509Certificate[1];
			chain[0] = certGen.getSelfCertificate(new X500Name("C=US; ST=CO; L=Denver; " +
					"O=Apache Traffic Control; OU=Apache Foundation; OU=Hosted by Traffic Control; " +
					"OU=CDNDefault; CN="+DEFAULT_SSL_KEY), new Date(System.currentTimeMillis() - 1000L * 60 ),
					(long) 3 * 365 * 24 * 3600, extensions);
			final PrivateKey serverPrivateKey = certGen.getPrivateKey();
			return new HandshakeData(DEFAULT_SSL_KEY, DEFAULT_SSL_KEY, chain, serverPrivateKey);
		}
		catch (Exception e) {
			log.error("Could not generate the default certificate: "+e.getMessage(),e);
			return null;
		}
	}

	public List<String> getAliases() {
		return new ArrayList<>(handshakeDataMap.keySet());
	}

	public HandshakeData getHandshakeData(final String alias) {
		return handshakeDataMap.get(alias);
	}

	public Map<String, HandshakeData> getHandshakeData() {
	    return handshakeDataMap;
    }

	public void setEndPoint(final RouterNioEndpoint routerNioEndpoint) {
		sslEndpoint = routerNioEndpoint;
	}

	@SuppressWarnings("PMD.AccessorClassGeneration")
	private static class CertificateRegistryHolder {
		private static final CertificateRegistry DELIVERY_SERVICE_CERTIFICATES = new CertificateRegistry();
	}

	@SuppressWarnings({"PMD.CyclomaticComplexity", "PMD.AvoidDeeplyNestedIfStmts", "PMD.NPathComplexity"})
	synchronized public void importCertificateDataList(final List<CertificateData> certificateDataList) {
		final Map<String, HandshakeData> changes = new HashMap<>();
		final Map<String, HandshakeData> master = new HashMap<>();

		// find CertificateData which has changed
		for (final CertificateData certificateData : certificateDataList) {
			try {
				final String alias = certificateData.alias();
				if (!master.containsKey(alias)) {
					final HandshakeData handshakeData = certificateDataConverter.toHandshakeData(certificateData);
					if (handshakeData != null) {
						master.put(alias, handshakeData);
						if (!certificateData.equals(previousData.get(alias))) {
							changes.put(alias, handshakeData);
							log.warn("Imported handshake data with alias " + alias);
						}
					}
				}
				else {
					log.error("An TLS certificate already exists in the registry for host: "+alias+" There can be " +
							"only one!" );
				}
			} catch (Exception e) {
				log.error("Failed to import certificate data for delivery service: '" + certificateData.getDeliveryservice() + "', hostname: '" + certificateData.getHostname() + "'");
			}
		}
		// find CertificateData which has been removed
		boolean delPreviousDefault = false;
		for (final String alias : previousData.keySet())
		{
			if (!master.containsKey(alias) && sslEndpoint != null)
			{
				delPreviousDefault = DEFAULT_SSL_KEY.equals(alias);
				final String hostname = previousData.get(alias).getHostname();
				sslEndpoint.removeSslHostConfig(hostname);
				log.warn("Removed handshake data with hostname " + hostname);
			}
		}
		// store the result for the next import
		previousData.clear();
		for (final CertificateData certificateData : certificateDataList) {
			final String alias = certificateData.alias();
			if (!previousData.containsKey(alias) && master.containsKey(alias)) {
				previousData.put(alias, certificateData);
			}
		}

		// Check to see if a Default cert has been provided by Traffic Ops
		if (!master.containsKey(DEFAULT_SSL_KEY)){
			// Check to see if a Default cert has been provided/created previously
			if (!delPreviousDefault && handshakeDataMap.containsKey(DEFAULT_SSL_KEY)) {
				master.put(DEFAULT_SSL_KEY, handshakeDataMap.get(DEFAULT_SSL_KEY));
			}else{
				// create a new default certificate
				final HandshakeData defaultHd = createDefaultSsl();
				if (defaultHd == null){
					log.error("Failed to initialize the CertificateRegistry because of a problem with the 'default' " +
							"certificate.  Returning the Certificate Registry without a default.");
					return;
				}
				master.put(DEFAULT_SSL_KEY, defaultHd);
			}
		}
		handshakeDataMap = master;

		if (sslEndpoint != null) {
			sslEndpoint.replaceSSLHosts(changes);
		}
	}

	public CertificateDataConverter getCertificateDataConverter() {
		return certificateDataConverter;
	}

	public void setCertificateDataConverter(final CertificateDataConverter certificateDataConverter) {
		this.certificateDataConverter = certificateDataConverter;
	}
}
