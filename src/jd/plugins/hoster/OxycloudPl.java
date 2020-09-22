//jDownloader - Downloadmanager
//Copyright (C) 2016  JD-Team support@jdownloader.org
//
//This program is free software: you can redistribute it and/or modify
//it under the terms of the GNU General Public License as published by
//the Free Software Foundation, either version 3 of the License, or
//(at your option) any later version.
//
//This program is distributed in the hope that it will be useful,
//but WITHOUT ANY WARRANTY; without even the implied warranty of
//MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
//GNU General Public License for more details.
//
//You should have received a copy of the GNU General Public License
//along with this program.  If not, see <http://www.gnu.org/licenses/>.
package jd.plugins.hoster;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.appwork.storage.JSonStorage;
import org.appwork.storage.StorageException;
import org.appwork.storage.TypeRef;
import org.appwork.utils.Exceptions;
import org.appwork.utils.StringUtils;
import org.appwork.utils.formatter.SizeFormatter;
import org.appwork.utils.formatter.TimeFormatter;
import org.jdownloader.plugins.components.YetiShareCore;
import org.jdownloader.scripting.JavaScriptEngineFactory;

import jd.PluginWrapper;
import jd.controlling.AccountController;
import jd.http.Browser;
import jd.http.URLConnectionAdapter;
import jd.plugins.Account;
import jd.plugins.Account.AccountType;
import jd.plugins.AccountInfo;
import jd.plugins.AccountUnavailableException;
import jd.plugins.DownloadLink;
import jd.plugins.DownloadLink.AvailableStatus;
import jd.plugins.HostPlugin;
import jd.plugins.LinkStatus;
import jd.plugins.Plugin;
import jd.plugins.PluginException;
import jd.plugins.components.PluginJSonUtils;

@HostPlugin(revision = "$Revision$", interfaceVersion = 2, names = {}, urls = {})
public class OxycloudPl extends YetiShareCore {
    public OxycloudPl(PluginWrapper wrapper) {
        super(wrapper);
        this.enablePremium(getPurchasePremiumURL());
    }

    private static final String PROPERTY_needs_premium      = "needs_premium";
    private static final String PROPERTY_account_max_chunks = "max_chunks";

    /**
     * DEV NOTES YetiShare<br />
     ****************************
     * mods: See overridden functions<br />
     * limit-info:<br />
     * captchatype-info: 2020-09-03: null<br />
     * other: <br />
     */
    public static List<String[]> getPluginDomains() {
        final List<String[]> ret = new ArrayList<String[]>();
        // each entry in List<String[]> will result in one PluginForHost, Plugin.getHost() will return String[0]->main domain
        ret.add(new String[] { "oxycloud.pl" });
        return ret;
    }

    public static String[] getAnnotationNames() {
        return buildAnnotationNames(getPluginDomains());
    }

    @Override
    public String[] siteSupportedNames() {
        return buildSupportedNames(getPluginDomains());
    }

    public static String[] getAnnotationUrls() {
        return YetiShareCore.buildAnnotationUrls(getPluginDomains());
    }

    @Override
    public boolean isResumeable(final DownloadLink link, final Account account) {
        if (account != null && account.getType() == AccountType.FREE) {
            /* Free Account */
            return true;
        } else if (account != null && account.getType() == AccountType.PREMIUM) {
            /* Premium account */
            return true;
        } else {
            /* Free(anonymous) and unknown account type */
            return true;
        }
    }

    public int getMaxChunks(final Account account) {
        if (account == null) {
            return 1;
        } else if (this.supports_api()) {
            /* Limits may vary depending on account type - controlled via API! */
            return account.getIntegerProperty(PROPERTY_account_max_chunks, 1);
        } else {
            /* Return default limit for account mode */
            return -10;
        }
    }

    @Override
    public boolean canHandle(final DownloadLink link, final Account account) throws Exception {
        if (link.getBooleanProperty(PROPERTY_needs_premium, false) && (account == null || account.getType() != AccountType.PREMIUM)) {
            return false;
        } else {
            return true;
        }
    }

    @Override
    public int getMaxSimultanFreeDownloadNum() {
        return 10;
    }

    public int getMaxSimultaneousFreeAccountDownloads() {
        return 10;
    }

    @Override
    public int getMaxSimultanPremiumDownloadNum() {
        return 10;
    }

    @Override
    public AvailableStatus requestFileInformation(final DownloadLink link, final Account account, final boolean isDownload) throws Exception {
        /* 2020-09-09: Small experiment */
        // final Account apiAccount = getApiAccount();
        // if (apiAccount != null && !isDownload) {
        // return requestFileInformationAPI(link, apiAccount);
        // }
        if (!link.isNameSet()) {
            setWeakFilename(link);
        }
        br.setFollowRedirects(true);
        prepBrowserWebsite(this.br);
        /* 2020-09-09: Their URLs may be direct-URLs (even without account) ... */
        final URLConnectionAdapter con = br.openGetConnection(link.getPluginPatternMatcher());
        if (this.isDownloadableContent(con)) {
            link.setFinalFileName(Plugin.getFileNameFromDispositionHeader(con));
            link.setDownloadSize(con.getCompleteContentLength());
            con.disconnect();
            return AvailableStatus.TRUE;
        } else {
            br.followConnection();
            return super.requestFileInformation(link, account, isDownload);
        }
    }

    @Override
    public String[] scanInfo(final DownloadLink link, final String[] fileInfo) {
        super.scanInfo(link, fileInfo);
        if (supports_availablecheck_over_info_page(link)) {
            /* 2020-08-14: Special */
            if (StringUtils.isEmpty(fileInfo[1])) {
                fileInfo[1] = br.getRegex("File size</th>\\s*<td>([^<>\"]+)</td>").getMatch(0);
            }
        }
        return fileInfo;
    }

    @Override
    protected AccountInfo fetchAccountInfoWebsite(final Account account) throws Exception {
        final AccountInfo ai = new AccountInfo();
        loginWebsite(account, true);
        final AccountInfo apiAccInfo = fetchAccountInfoWebsiteAPI(account);
        if (apiAccInfo != null) {
            logger.info("Found AccountInfo via API --> Prefer this over website AccountInfo");
            return apiAccInfo;
        }
        this.getPage("/download-limits-calculator");
        final String trafficLeft = br.getRegex("class=\"fa fa-download\"></i>([^<>]*)</span>").getMatch(0);
        final String expireDate = br.getRegex("This package is active until (\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2})").getMatch(0);
        if (trafficLeft != null && expireDate != null) {
            ai.setValidUntil(TimeFormatter.getMilliSeconds(expireDate, "yyyy-MM-dd hh:mm", Locale.ENGLISH), this.br);
            ai.setTrafficLeft(SizeFormatter.getSize(trafficLeft));
            this.setAccountLimitsByType(account, AccountType.PREMIUM);
        } else {
            this.setAccountLimitsByType(account, AccountType.FREE);
            ai.setUnlimitedTraffic();
        }
        return ai;
    }

    @Override
    protected AccountInfo fetchAccountInfoWebsiteAPI(final Account account) {
        try {
            final Browser brc = br.cloneBrowser();
            this.getPage(brc, getAccountEditURL());
            String key1 = brc.getRegex("name=\"user_settings_form\\[apiKeyPublic\\]\"[^>]* value=\"([^\"]+)\"").getMatch(0);
            String key2 = brc.getRegex("name=\"user_settings_form\\[apiKeySecret\\]\"[^>]* value=\"([^\"]+)\"").getMatch(0);
            if (this.isAPICredential(key1) && this.isAPICredential(key2)) {
                logger.info("Found possibly valid API login credentials");
                try {
                    final AccountInfo apiAccInfo = this.fetchAccountInfoAPI(account, key1, key2);
                    if (apiAccInfo != null) {
                        logger.info("API AccountInfo found");
                        return apiAccInfo;
                    } else {
                        logger.info("Failed to find API AccountInfo");
                    }
                } catch (final Throwable e) {
                    logger.info("API handling inside website handling failed");
                }
            }
        } catch (final Throwable e) {
            e.printStackTrace();
        }
        return null;
    }

    @Override
    protected String getAccountEditURL() {
        return "/account";
    }
    /* *************************** PUT API RELATED METHODS HERE *************************** */

    @Override
    protected String getAPIBase() {
        return "https://" + this.getHost() + "/api/v1";
    }

    @Override
    protected String getAccountOverviewURL() {
        return this.getMainPage() + "/account";
    }

    @Override
    protected boolean supports_api() {
        return true;
    }

    @Override
    protected boolean isAPICredential(final String str) {
        /* 2020-09-03: Special: API allows auth via username and password instead of "Key1" and "Key2". */
        return str != null && str.matches(".{1,}");
    }

    /** Headers required for all custom built GET request that they've built on top of the official YetiShare API. */
    private void setAPIHeaders(final Browser br, final Account account) {
        br.getHeaders().put("authentication", this.getAPIAccessToken(account, account.getUser(), account.getPass()));
        br.getHeaders().put("account", this.getAPIAccountID(account, account.getUser(), account.getPass()));
    }

    @Override
    protected AccountInfo fetchAccountInfoAPI(final Account account, final String key1, final String key2) throws Exception {
        final AccountInfo ai = super.fetchAccountInfoAPI(account, key1, key2);
        /* 2020-08-26: They've built some stuff on top of the normal YetiShare API --> Handle this here */
        final Browser brc = br.cloneBrowser();
        setAPIHeaders(brc, account);
        this.getPage(brc, this.getAPIBase() + "/package/limits");
        Map<String, Object> entries = JSonStorage.restoreFromString(brc.toString(), TypeRef.HASHMAP);
        entries = (Map<String, Object>) entries.get("account");
        final String accType = (String) entries.get("type");
        final boolean isUnlimited = ((Boolean) entries.get("isUnlimited")).booleanValue();
        final boolean isPremium = ((Boolean) entries.get("isPremium")).booleanValue();
        if (isUnlimited) {
            ai.setUnlimitedTraffic();
        } else {
            /*
             * 2020-08-26: These values are usually null for free accounts but we'll try to set them anyways in case they change this in the
             * future.
             */
            // long totalBytesLeft = 0;
            long dailyBytesLeft = 0;
            long maxDailyBytes = 0;
            // final Object totalBytesLeftO = entries.get("totalBytesLeft");
            final Object dailyBytesLeftO = entries.get("dailyBytesLeft");
            final Object maxDailyBytesO = entries.get("maxDailyBytes");
            // if (totalBytesLeftO != null && totalBytesLeftO instanceof Number) {
            // totalBytesLeft = ((Number) totalBytesLeftO).longValue();
            // }
            if (dailyBytesLeftO != null && dailyBytesLeftO instanceof Number) {
                dailyBytesLeft = ((Number) dailyBytesLeftO).longValue();
            }
            if (maxDailyBytesO != null && maxDailyBytesO instanceof Number) {
                maxDailyBytes = ((Number) maxDailyBytesO).longValue();
            }
            /* 2020-09-10: Display daily limits in account manager so it resembles their website. */
            ai.setTrafficLeft(dailyBytesLeft);
            if (maxDailyBytes > 0) {
                ai.setTrafficMax(maxDailyBytes);
            }
            if (dailyBytesLeft <= 0) {
                logger.warning("No daily traffic left - account probably can't be used for downloading today");
            }
        }
        if ("paid".equalsIgnoreCase(accType) || isPremium) {
            account.setType(AccountType.PREMIUM);
        } else {
            account.setType(AccountType.FREE);
        }
        final int maxSimultaneousDownloads;
        final Object maxSimultaneousDownloadsO = entries.get("maxConcurrentDownloads");
        if (maxSimultaneousDownloadsO != null && maxSimultaneousDownloadsO instanceof Number) {
            maxSimultaneousDownloads = ((Number) maxSimultaneousDownloadsO).intValue();
        } else {
            /* Fallback */
            maxSimultaneousDownloads = 1;
        }
        if (maxSimultaneousDownloads > 0) {
            account.setMaxSimultanDownloads(maxSimultaneousDownloads);
        }
        final Object maxNumberOfChunksO = entries.get("maxNumberOfActiveDownloads");
        if (maxNumberOfChunksO != null && maxNumberOfChunksO instanceof Number) {
            final int maxNumberOfChunks = ((Number) maxSimultaneousDownloadsO).intValue();
            if (maxNumberOfChunks > 1) {
                account.setProperty(PROPERTY_account_max_chunks, -maxNumberOfChunks);
            }
        }
        logger.info("Status: " + accType + " | MaxDls: " + maxSimultaneousDownloads + " | MaxChunks: " + account.getLongProperty(PROPERTY_account_max_chunks, 1));
        return ai;
    }

    private Account getApiAccount() {
        final Account account = AccountController.getInstance().getValidAccount(this.getHost());
        if (account != null && this.getAPIAccessToken(account, account.getUser(), account.getPass()) != null && this.getAPIAccountID(account, account.getUser(), account.getPass()) != null) {
            return account;
        } else {
            return null;
        }
    }

    @Override
    protected void handleDownloadAPI(final DownloadLink link, final Account account) throws StorageException, Exception {
        /** 2020-08-31: Custom built method on top of the official YetiShare API. */
        final String directlinkproperty = getDownloadModeDirectlinkProperty(account);
        String dllink = this.checkDirectLink(link, account);
        if (dllink == null) {
            this.loginAPI(account, account.getUser(), account.getPass(), false);
            this.setAPIHeaders(br, account);
            br.getPage(this.getAPIBase() + "/file/download/" + this.getFUID(link));
            this.checkErrorsAPI(this.br, link, account);
            dllink = PluginJSonUtils.getJson(this.br, "url");
            if (StringUtils.isEmpty(dllink)) {
                /* Do not throw plugin defect because we're using an API */
                throw new PluginException(LinkStatus.ERROR_TEMPORARILY_UNAVAILABLE, "Failed to find final downloadurl");
            }
            final boolean resume = this.isResumeable(link, account);
            final int maxchunks = this.getMaxChunks(account);
            dl = jd.plugins.BrowserAdapter.openDownload(br, link, dllink, resume, maxchunks);
        }
        final URLConnectionAdapter con = dl.getConnection();
        /*
         * Save directurl before download-attempt as it should be valid even if it e.g. fails because of server issue 503 (= too many
         * connections) --> Should work fine after the next try.
         */
        link.setProperty(directlinkproperty, con.getURL().toString());
        try {
            checkResponseCodeErrors(con);
        } catch (final PluginException e) {
            try {
                br.followConnection(true);
            } catch (IOException ioe) {
                throw Exceptions.addSuppressed(e, ioe);
            }
            throw e;
        }
        if (!isDownloadableContent(con)) {
            try {
                br.followConnection(true);
            } catch (IOException e) {
                logger.log(e);
            }
            checkErrors(link, account);
            throw new PluginException(LinkStatus.ERROR_TEMPORARILY_UNAVAILABLE, "Final downloadurl did not lead to downloadable content");
        }
        dl.setFilenameFix(isContentDispositionFixRequired(dl, con, link));
        dl.startDownload();
    }

    @Override
    protected void checkErrorsAPI(final Browser br, final DownloadLink link, final Account account) throws PluginException {
        Map<String, Object> entries = null;
        try {
            entries = JSonStorage.restoreFromString(br.toString(), TypeRef.HASHMAP);
        } catch (final Throwable e) {
            /* API response is not json */
            throw new AccountUnavailableException("Invalid API response", 1 * 60 * 1000l);
        }
        /* E.g. {"message":"Username could not be found.","result":false} */
        boolean result = true;
        String msg = null;
        try {
            result = ((Boolean) entries.get("result")).booleanValue();
            msg = (String) entries.get("message");
            if (StringUtils.isEmpty(msg)) {
                msg = (String) entries.get("response");
            }
        } catch (final Throwable e) {
        }
        if (!result) {
            if (StringUtils.isEmpty(msg)) {
                msg = "Unknown error";
            }
            if (msg.equalsIgnoreCase("Out of daily traffic")) {
                throw new PluginException(LinkStatus.ERROR_PREMIUM, msg, PluginException.VALUE_ID_PREMIUM_TEMP_DISABLE);
            }
            /* Ignore unknown errors - let template code handle them */
        }
        /* Check for basic errors */
        super.checkErrorsAPI(br, link, account);
    }

    @Override
    public boolean checkLinks(final DownloadLink[] urls) {
        final Account apiAccount = getApiAccount();
        if (apiAccount != null) {
            return massLinkcheckerAPI(urls, apiAccount);
        } else {
            /* No mass linkchecking possible */
            return false;
        }
    }

    protected final AvailableStatus requestFileInformationAPI(final DownloadLink link, final Account apiaccount) throws Exception {
        massLinkcheckerAPI(new DownloadLink[] { link }, apiaccount);
        if (link.getAvailableStatus() == AvailableStatus.FALSE) {
            throw new PluginException(LinkStatus.ERROR_FILE_NOT_FOUND);
        }
        return link.getAvailableStatus();
    }

    /**
     * Checks multiple URLs via API. Only works when an account with API credentials is given.
     */
    public boolean massLinkcheckerAPI(final DownloadLink[] urls, final Account apiAccount) {
        if (urls == null || urls.length == 0 || apiAccount == null) {
            return false;
        }
        boolean linkcheckerHasFailed = false;
        try {
            final Browser br = new Browser();
            this.setAPIHeaders(br, apiAccount);
            this.prepBrowser(br, getMainPage());
            br.setCookiesExclusive(true);
            final StringBuilder sb = new StringBuilder();
            final ArrayList<DownloadLink> links = new ArrayList<DownloadLink>();
            int index = 0;
            while (true) {
                links.clear();
                while (true) {
                    /*
                     * 2020-08-27: Tested for up to 100 items but we'll check max. 50 per request.
                     */
                    if (index == urls.length || links.size() == 50) {
                        break;
                    } else {
                        links.add(urls[index]);
                        index++;
                    }
                }
                sb.delete(0, sb.capacity());
                for (final DownloadLink dl : links) {
                    // sb.append("%0A");
                    sb.append(dl.getPluginPatternMatcher());
                    sb.append("%2C");
                }
                getPage(br, this.getAPIBase() + "/file/check?links=" + sb.toString());
                try {
                    this.checkErrorsAPI(br, links.get(0), null);
                } catch (final Throwable e) {
                    logger.log(e);
                    /* E.g. invalid apikey, broken serverside API. */
                    logger.info("Fatal failure");
                    return false;
                }
                LinkedHashMap<String, Object> entries = (LinkedHashMap<String, Object>) JavaScriptEngineFactory.jsonToJavaMap(br.toString());
                final ArrayList<Object> ressourcelist = (ArrayList<Object>) entries.get("files");
                for (final DownloadLink link : links) {
                    boolean foundResult = false;
                    final String fuid = this.getFUID(link);
                    for (final Object fileO : ressourcelist) {
                        entries = (LinkedHashMap<String, Object>) fileO;
                        final String url = (String) entries.get("url");
                        final String fuid_tmp = this.getFUIDFromURL(url);
                        if (fuid_tmp != null && fuid_tmp.equalsIgnoreCase(fuid)) {
                            foundResult = true;
                            break;
                        }
                    }
                    if (!foundResult) {
                        /**
                         * This should never happen!
                         */
                        logger.warning("WTF failed to find information for fuid: " + fuid);
                        linkcheckerHasFailed = true;
                        continue;
                    }
                    /* 2020-08-31: Filename- and filesize can even be given for offline items! */
                    final String status = (String) entries.get("status");
                    String filename = (String) entries.get("filename");
                    final long filesize = JavaScriptEngineFactory.toLong(entries.get("fileSize"), 0);
                    if (!StringUtils.isEmpty(filename)) {
                        link.setFinalFileName(filename);
                    }
                    if (filesize > 0) {
                        link.setDownloadSize(filesize);
                    }
                    if (!"online".equalsIgnoreCase(status)) {
                        link.setAvailable(false);
                        setWeakFilename(link);
                    } else {
                        link.setAvailable(true);
                        /* We don't care if one or both of these fields are not given though they should always be available! */
                        try {
                            link.setProperty(PROPERTY_needs_premium, ((Boolean) entries.get("isPremium")).booleanValue());
                            /* 2020-08-28: isPrivate = file will not be grabbed by search engines. Whoever has the URL can download it. */
                            // link.setProperty(PROPERTY_is_private, ((Boolean) entries.get("isPrivate")).booleanValue());
                        } catch (final Throwable e) {
                            logger.log(e);
                        }
                        /* 2020-08-28: CRC32 hash of the first file chunk (50MiB) */
                        // String hash = (String) entries.get("hash");
                        // if (!StringUtils.isEmpty(hash)) {
                        // hash = hash.replace("-", "");
                        // link.setMD5Hash(hash);
                        // }
                    }
                }
                if (index == urls.length) {
                    break;
                }
            }
        } catch (final Exception e) {
            logger.log(e);
            return false;
        }
        if (linkcheckerHasFailed) {
            return false;
        } else {
            return true;
        }
    }
}