//    jDownloader - Downloadmanager
//    Copyright (C) 2009  JD-Team support@jdownloader.org
//
//    This program is free software: you can redistribute it and/or modify
//    it under the terms of the GNU General Public License as published by
//    the Free Software Foundation, either version 3 of the License, or
//    (at your option) any later version.
//
//    This program is distributed in the hope that it will be useful,
//    but WITHOUT ANY WARRANTY; without even the implied warranty of
//    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
//    GNU General Public License for more details.
//
//    You should have received a copy of the GNU General Public License
//    along with this program.  If not, see <http://www.gnu.org/licenses/>.

package jd.controlling;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map.Entry;

import jd.config.SubConfiguration;
import jd.controlling.accountchecker.AccountChecker;
import jd.http.Browser;
import jd.plugins.Account;
import jd.plugins.AccountInfo;
import jd.plugins.LinkStatus;
import jd.plugins.PluginException;
import jd.plugins.PluginForHost;
import jd.utils.JDUtilities;

import org.appwork.scheduler.DelayedRunnable;
import org.appwork.shutdown.ShutdownController;
import org.appwork.shutdown.ShutdownEvent;
import org.appwork.storage.config.JsonConfig;
import org.appwork.utils.StringUtils;
import org.appwork.utils.event.Eventsender;
import org.appwork.utils.logging.Log;
import org.jdownloader.settings.AccountData;
import org.jdownloader.settings.AccountSettings;

public class AccountController implements AccountControllerListener {

    private static final long                                                    serialVersionUID = -7560087582989096645L;

    private static HashMap<String, ArrayList<Account>>                           hosteraccounts   = null;

    private static HashMap<Account, Long>                                        blockedAccounts  = new HashMap<Account, Long>();

    private static AccountController                                             INSTANCE         = new AccountController();

    private final Eventsender<AccountControllerListener, AccountControllerEvent> broadcaster      = new Eventsender<AccountControllerListener, AccountControllerEvent>() {

                                                                                                      @Override
                                                                                                      protected void fireEvent(final AccountControllerListener listener, final AccountControllerEvent event) {
                                                                                                          listener.onAccountControllerEvent(event);
                                                                                                      }

                                                                                                  };

    public Eventsender<AccountControllerListener, AccountControllerEvent> getBroadcaster() {
        return broadcaster;
    }

    private AccountSettings config;

    private DelayedRunnable delayedSaver;

    private AccountController() {
        super();
        config = JsonConfig.create(AccountSettings.class);
        ShutdownController.getInstance().addShutdownEvent(new ShutdownEvent() {

            @Override
            public void run() {
                save();
            }

            @Override
            public String toString() {
                return "save accounts...";
            }
        });
        hosteraccounts = loadAccounts();
        final Collection<ArrayList<Account>> accsc = hosteraccounts.values();
        for (final ArrayList<Account> accs : accsc) {
            for (final Account acc : accs) {
                acc.setAccountController(this);
            }
        }
        delayedSaver = new DelayedRunnable(IOEQ.TIMINGQUEUE, 5000, 30000) {

            @Override
            public void delayedrun() {
                save();
            }
        };
        broadcaster.addListener(this);
    }

    protected void save() {
        HashMap<String, ArrayList<AccountData>> ret = new HashMap<String, ArrayList<AccountData>>();
        synchronized (hosteraccounts) {
            for (Iterator<Entry<String, ArrayList<Account>>> it = hosteraccounts.entrySet().iterator(); it.hasNext();) {
                Entry<String, ArrayList<Account>> next = it.next();
                if (next.getValue().size() > 0) {
                    ArrayList<AccountData> list = new ArrayList<AccountData>();
                    ret.put(next.getKey(), list);
                    for (Account a : next.getValue()) {
                        list.add(AccountData.create(a));
                    }
                }
            }
        }
        config.setAccounts(ret);
    }

    public AccountInfo updateAccountInfo(final Account account, final boolean forceupdate) {
        AccountInfo ai = account.getAccountInfo();
        if (!forceupdate) {
            if (account.lastUpdateTime() != 0) {
                if (ai != null && ai.isExpired()) {
                    account.setEnabled(false);
                    this.broadcaster.fireEvent(new AccountControllerEvent(this, AccountControllerEvent.Types.EXPIRED, account));
                    /* account is expired, no need to update */
                    return ai;
                }
                if (!account.isValid()) {
                    account.setEnabled(false);
                    this.broadcaster.fireEvent(new AccountControllerEvent(this, AccountControllerEvent.Types.INVALID, account));
                    /* account is invalid, no need to update */
                    return ai;
                }
            }
            if ((System.currentTimeMillis() - account.lastUpdateTime()) < account.getRefreshTimeout()) {
                /*
                 * account was checked before, timeout for recheck not reached,
                 * no need to update
                 */
                return ai;
            }
        }
        final PluginForHost plugin = JDUtilities.getNewPluginForHostInstance(account.getHoster());
        if (plugin == null) {
            Log.L.severe("AccountCheck: Failed because plugin " + account.getHoster() + " is missing!");
            account.setEnabled(false);
            return null;
        }
        String whoAmI = account.getUser() + "->" + account.getHoster();
        JDPluginLogger logger = new JDPluginLogger("AccountCheck: " + whoAmI);
        plugin.setLogger(logger);
        Browser br = new Browser();
        br.setLogger(logger);
        plugin.setBrowser(br);
        try {
            /* not every plugin sets this info correct */
            account.setValid(true);
            /* get previous account info and resets info for new update */
            ai = account.getAccountInfo();
            if (ai != null) {
                /* reset expired and setValid */
                ai.setExpired(false);
                ai.setValidUntil(-1);
            }
            Thread currentThread = Thread.currentThread();
            ClassLoader oldClassLoader = currentThread.getContextClassLoader();
            try {
                /*
                 * make sure the current Thread uses the PluginClassLoaderChild
                 * of the Plugin in use
                 */
                currentThread.setContextClassLoader(plugin.getLazyP().getClassLoader());
                ai = plugin.fetchAccountInfo(account);
            } finally {
                account.setUpdateTime(System.currentTimeMillis());
                currentThread.setContextClassLoader(oldClassLoader);
            }
            if (ai == null) {
                /* not every plugin has fetchAccountInfo */
                account.setAccountInfo(null);
                this.broadcaster.fireEvent(new AccountControllerEvent(this, AccountControllerEvent.Types.UPDATE, account));
                return null;
            }
            account.setAccountInfo(ai);
            if (ai.isExpired()) {
                logger.clear();
                logger.info("Account " + whoAmI + " is expired!");
                this.broadcaster.fireEvent(new AccountControllerEvent(this, AccountControllerEvent.Types.EXPIRED, account));
            } else if (!account.isValid()) {
                account.setEnabled(false);
                logger.clear();
                logger.info("Account " + whoAmI + " is invalid!");
                this.broadcaster.fireEvent(new AccountControllerEvent(this, AccountControllerEvent.Types.INVALID, account));
            } else {
                logger.clear();
                this.broadcaster.fireEvent(new AccountControllerEvent(this, AccountControllerEvent.Types.UPDATE, account));
            }
        } catch (final Throwable e) {
            if (e instanceof PluginException) {
                PluginException pe = (PluginException) e;
                ai = account.getAccountInfo();
                if (ai == null) {
                    ai = new AccountInfo();
                    account.setAccountInfo(ai);
                }
                if ((pe.getLinkStatus() == LinkStatus.ERROR_PREMIUM)) {
                    if (pe.getValue() == PluginException.VALUE_ID_PREMIUM_TEMP_DISABLE) {
                        logger.clear();
                        logger.info("Account " + whoAmI + " traffic limit reached!");
                        account.setTempDisabled(true);
                        account.getAccountInfo().setTrafficLeft(0);
                        this.broadcaster.fireEvent(new AccountControllerEvent(this, AccountControllerEvent.Types.UPDATE, account));
                        return ai;
                    } else if (pe.getValue() == PluginException.VALUE_ID_PREMIUM_DISABLE) {
                        account.setEnabled(false);
                        account.setValid(false);
                        if (StringUtils.isEmpty(ai.getStatus())) ai.setStatus("Invalid Account!");
                        logger.clear();
                        logger.info("Account " + whoAmI + " is invalid!");
                        this.broadcaster.fireEvent(new AccountControllerEvent(this, AccountControllerEvent.Types.INVALID, account));
                        return ai;
                    }
                }
            }
            logger.severe("AccountCheck: Failed because of exception");
            logger.severe(JDLogger.getStackTrace(e));
            /* move download log into global log */
            account.setAccountInfo(null);
            account.setEnabled(false);
            account.setValid(false);
            this.broadcaster.fireEvent(new AccountControllerEvent(this, AccountControllerEvent.Types.INVALID, account));
        }
        logger.logInto(JDLogger.getLogger());
        return ai;
    }

    public static AccountController getInstance() {
        return INSTANCE;
    }

    private synchronized HashMap<String, ArrayList<Account>> loadAccounts() {
        HashMap<String, ArrayList<AccountData>> dat = config.getAccounts();
        if (dat == null) {
            try {
                dat = restore();
            } catch (final Throwable e) {
                Log.exception(e);
            }
        }
        if (dat == null) {
            dat = new HashMap<String, ArrayList<AccountData>>();
        }
        HashMap<String, ArrayList<Account>> ret = new HashMap<String, ArrayList<Account>>();
        for (Iterator<Entry<String, ArrayList<AccountData>>> it = dat.entrySet().iterator(); it.hasNext();) {
            Entry<String, ArrayList<AccountData>> next = it.next();
            if (next.getValue().size() > 0) {
                ArrayList<Account> list = new ArrayList<Account>();
                ret.put(next.getKey(), list);
                for (AccountData ad : next.getValue()) {
                    Account acc;
                    list.add(acc = ad.toAccount());
                    /*
                     * make sure hostername is set and share same String
                     * instance
                     */
                    acc.setHoster(next.getKey());
                }
            }
        }
        return ret;
    }

    /**
     * Restores accounts from old database
     * 
     * @return
     */
    private HashMap<String, ArrayList<AccountData>> restore() {
        SubConfiguration sub = SubConfiguration.getConfig("AccountController");
        HashMap<String, ArrayList<HashMap<String, Object>>> tree = sub.getGenericProperty("accountlist", new HashMap<String, ArrayList<HashMap<String, Object>>>());
        HashMap<String, ArrayList<AccountData>> ret = new HashMap<String, ArrayList<AccountData>>();

        for (Iterator<Entry<String, ArrayList<HashMap<String, Object>>>> it = tree.entrySet().iterator(); it.hasNext();) {
            Entry<String, ArrayList<HashMap<String, Object>>> next = it.next();
            if (next.getValue().size() > 0) {
                ArrayList<AccountData> list = new ArrayList<AccountData>();
                ret.put(next.getKey(), list);
                for (HashMap<String, Object> a : next.getValue()) {
                    AccountData ac;
                    list.add(ac = new AccountData());
                    ac.setUser((String) a.get("user"));
                    ac.setPassword((String) a.get("pass"));
                    ac.setEnabled("true".equals(a.containsKey("enabled")));
                }
            }
        }
        config.setAccounts(ret);
        return ret;
    }

    @Deprecated
    public void addAccount(final PluginForHost pluginForHost, final Account account) {
        account.setHoster(pluginForHost.getHost());
        addAccount(account);
    }

    public boolean isAccountBlocked(final Account account) {
        synchronized (blockedAccounts) {
            Long ret = blockedAccounts.get(account);
            if (ret == null) return false;
            if (System.currentTimeMillis() > ret) {
                /*
                 * timeout is over, lets remove the account as it is no longer
                 * blocked
                 */
                blockedAccounts.remove(account);
                return false;
            }
            return true;
        }
    }

    public void addAccountBlocked(final Account account, final long value) {
        synchronized (blockedAccounts) {
            long blockedTime = Math.max(0, value);
            if (blockedTime == 0) {
                Log.L.info("Invalid AccountBlock timeout! set 30 mins!");
                blockedTime = 60 * 60 * 1000l;
            }
            blockedAccounts.put(account, System.currentTimeMillis() + blockedTime);
        }
    }

    /* remove accountblock for given account or all if account is null */
    public void removeAccountBlocked(final Account account) {
        synchronized (blockedAccounts) {
            if (account == null) {
                blockedAccounts.clear();
            } else {
                blockedAccounts.remove(account);
            }
        }
    }

    /* returns a list of all available accounts for given host */
    public List<Account> list(String host) {
        ArrayList<Account> ret = new ArrayList<Account>();
        synchronized (hosteraccounts) {
            if (StringUtils.isEmpty(host)) {
                for (String hoster : hosteraccounts.keySet()) {
                    ArrayList<Account> ret2 = hosteraccounts.get(hoster);
                    if (ret2 != null) ret.addAll(ret2);
                }
            } else {
                ArrayList<Account> ret2 = hosteraccounts.get(host);
                if (ret2 != null) ret.addAll(ret2);
            }
        }
        return ret;
    }

    /* returns a list of all available accounts */
    public List<Account> list() {
        return list(null);
    }

    /* checks for available accounts for given host */
    public boolean hasAccounts(final String host) {
        ArrayList<Account> ret = null;
        synchronized (hosteraccounts) {
            ret = hosteraccounts.get(host);
        }
        return (ret != null && ret.size() > 0);
    }

    public void addAccount(final Account account) {
        if (account == null) return;
        synchronized (hosteraccounts) {
            ArrayList<Account> accs = hosteraccounts.get(account.getHoster());
            if (accs == null) {
                accs = new ArrayList<Account>();
                hosteraccounts.put(account.getHoster(), accs);
            }
            for (final Account acc : accs) {
                if (acc.equals(account)) return;
            }
            accs.add(account);
            account.setAccountController(this);
        }
        this.broadcaster.fireEvent(new AccountControllerEvent(this, AccountControllerEvent.Types.ADDED, account));
    }

    public boolean removeAccount(final Account account) {
        if (account == null) { return false; }
        /* remove reference to AccountController */
        account.setAccountController(null);
        removeAccountBlocked(account);
        synchronized (hosteraccounts) {
            ArrayList<Account> accs = hosteraccounts.get(account.getHoster());
            if (accs == null || !accs.remove(account)) return false;
            if (accs.size() == 0) hosteraccounts.remove(account.getHoster());
        }
        this.broadcaster.fireEvent(new AccountControllerEvent(this, AccountControllerEvent.Types.REMOVED, account));
        return true;
    }

    public void onAccountControllerEvent(final AccountControllerEvent event) {
        Account acc = null;
        switch (event.getType()) {
        case ADDED:
            org.jdownloader.settings.staticreferences.CFG_GENERAL.USE_AVAILABLE_ACCOUNTS.setValue(true);
            delayedSaver.run();
            break;
        case REMOVED:
            delayedSaver.run();
            return;
        }
        if (event.isRecheckRequired()) {
            /* event tells us to recheck the account */
            acc = event.getParameter();
            if (acc != null) AccountChecker.getInstance().check(acc, true);
        }
    }

    @Deprecated
    public Account getValidAccount(final PluginForHost pluginForHost) {
        LinkedList<Account> ret = getValidAccounts(pluginForHost.getHost());
        if (ret != null && ret.size() > 0) return ret.getFirst();
        return null;
    }

    public LinkedList<Account> getValidAccounts(final String host) {
        LinkedList<Account> ret = null;
        synchronized (hosteraccounts) {
            final ArrayList<Account> accounts = hosteraccounts.get(host);
            if (accounts == null || accounts.size() == 0) return null;
            ret = new LinkedList<Account>(accounts);
        }
        Iterator<Account> it = ret.iterator();
        while (it.hasNext()) {
            Account next = it.next();
            if (!next.isEnabled() || !next.isValid() || next.isTempDisabled() || isAccountBlocked(next)) {
                /* we remove every invalid/disabled/tempdisabled/blocked account */
                it.remove();
            }
        }
        return ret;
    }

    @SuppressWarnings("unchecked")
    @Deprecated
    public ArrayList<Account> getAllAccounts(String string) {
        return (ArrayList<Account>) list(string);
    }
}