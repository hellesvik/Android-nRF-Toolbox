package no.nordicsemi.android.nrftoolbox.view

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import no.nordicsemi.android.nrftoolbox.BuildConfig
import no.nordicsemi.android.nrftoolbox.ProfileDestination
import no.nordicsemi.android.nrftoolbox.R
import no.nordicsemi.android.nrftoolbox.viewmodel.HomeViewModel
import no.nordicsemi.android.theme.view.TitleAppBar

private const val DFU_LINK = "https://github.com/NordicSemiconductor/Android-DFU-Library"

@Composable
fun HomeScreen() {
    val viewModel: HomeViewModel = hiltViewModel()
    val state = viewModel.state.collectAsState().value

    Column {
        TitleAppBar(stringResource(id = R.string.app_name))

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp)
            ) {
                Spacer(modifier = Modifier.height(16.dp))

                Text(
                    text = stringResource(id = R.string.viewmodel_profiles),
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.fillMaxWidth(),
                )

                Spacer(modifier = Modifier.height(16.dp))

                FeatureButton(R.drawable.ic_bps, R.string.bps_module, R.string.bps_module_full) {
                    viewModel.openProfile(ProfileDestination.BPS)
                }

                Spacer(modifier = Modifier.height(16.dp))

                FeatureButton(R.drawable.ic_gls, R.string.gls_module, R.string.gls_module_full) {
                    viewModel.openProfile(ProfileDestination.GLS)
                }

                Spacer(modifier = Modifier.height(16.dp))

                Text(
                    text = stringResource(id = R.string.service_profiles),
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.fillMaxWidth(),
                )

                Spacer(modifier = Modifier.height(16.dp))

                FeatureButton(R.drawable.ic_csc, R.string.csc_module, R.string.csc_module_full, state.isCSCModuleRunning) {
                    viewModel.openProfile(ProfileDestination.CSC)
                }

                Spacer(modifier = Modifier.height(16.dp))

                FeatureButton(R.drawable.ic_hrs, R.string.hrs_module, R.string.hrs_module_full, state.isHRSModuleRunning) {
                    viewModel.openProfile(ProfileDestination.HRS)
                }

                Spacer(modifier = Modifier.height(16.dp))

                FeatureButton(R.drawable.ic_hts, R.string.hts_module, R.string.hts_module_full, state.isHTSModuleRunning) {
                    viewModel.openProfile(ProfileDestination.HTS)
                }

                Spacer(modifier = Modifier.height(16.dp))

                FeatureButton(R.drawable.ic_rscs, R.string.rscs_module, R.string.rscs_module_full, state.isRSCSModuleRunning) {
                    viewModel.openProfile(ProfileDestination.RSCS)
                }

                Spacer(modifier = Modifier.height(16.dp))

                FeatureButton(R.drawable.ic_prx, R.string.prx_module, R.string.prx_module_full, state.isPRXModuleRunning) {
                    viewModel.openProfile(ProfileDestination.PRX)
                }

                Spacer(modifier = Modifier.height(16.dp))

                FeatureButton(R.drawable.ic_cgm, R.string.cgm_module, R.string.cgm_module_full, state.isCGMModuleRunning) {
                    viewModel.openProfile(ProfileDestination.CGMS)
                }

                Spacer(modifier = Modifier.height(16.dp))

                Text(
                    text = stringResource(id = R.string.utils_services),
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.fillMaxWidth(),
                )

                Spacer(modifier = Modifier.height(16.dp))

                FeatureButton(R.drawable.ic_uart, R.string.uart_module, R.string.uart_module_full, state.isUARTModuleRunning) {
                    viewModel.openProfile(ProfileDestination.UART)
                }

                Spacer(modifier = Modifier.height(16.dp))

                val uriHandler = LocalUriHandler.current
                FeatureButton(R.drawable.ic_dfu, R.string.dfu_module, R.string.dfu_module_full) {
                    uriHandler.openUri(DFU_LINK)
                }

                Spacer(modifier = Modifier.height(16.dp))

                Text(
                    text = BuildConfig.VERSION_NAME,
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(16.dp))
            }
        }
    }
}